package org.rama.demo.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.IdempotentMutation;
import org.rama.entity.system.RequestDedup;
import org.rama.entity.system.RequestDedup.Status;
import org.rama.repository.system.RequestDedupRepository;
import org.rama.service.environment.EnvironmentService;
import org.rama.service.idempotency.SignatureResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: "thread A claims PENDING, simulated crash (kill the transaction by
 * entityManager.getTransaction().rollback() mid-flight), thread B picks up,
 * runs the work itself, leaves COMPLETED."
 *
 * We model the crash by directly inserting a PENDING row whose response_json
 * is null — the same state thread A would leave after crashing post-claim
 * but pre-completion. Then we call the annotated mutation and verify that
 * the aspect sees PENDING, runs the work itself, and transitions the row
 * to COMPLETED with the work's response.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
@Import({IdempotencyCrashRecoveryIT.TestMutation.class, IdempotencyCrashRecoveryIT.ClockOverride.class})
class IdempotencyCrashRecoveryIT {

    @Autowired TestMutation mutation;
    @Autowired RequestDedupRepository repository;
    @Autowired SignatureResolver signatureResolver;
    @Autowired EnvironmentService environmentService;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx.execute(s -> {
            repository.deleteAll();
            return null;
        });
        TestMutation.invocations.set(0);
    }

    @Test
    void pendingRowFromCrashedThread_picksUpAndCompletes() {
        Object[] args = {123L};
        String signature = signatureResolver.resolve(args);

        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");
        RequestDedup stranded = new RequestDedup();
        stranded.setId(signature);
        stranded.setMethod("crashed-mutation");
        stranded.setUsername(environmentService.getCurrentUsername());
        stranded.setStatus(Status.PENDING);
        stranded.setCreatedAt(now);
        stranded.setExpiresAt(now.plusSeconds(30));
        stranded.setResponseJson(null);
        tx.execute(s -> {
            repository.save(stranded);
            return null;
        });

        String result = mutation.makePayment(123L);

        assertThat(TestMutation.invocations.get())
                .as("aspect runs the work because row was PENDING after crash")
                .isEqualTo(1);
        assertThat(result).isEqualTo("paid-123-#1");

        RequestDedup after = repository.findById(signature).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(after.getResponseJson()).isEqualTo("\"paid-123-#1\"");
    }

    @Slf4j
    @Component
    static class TestMutation {

        static final AtomicInteger invocations = new AtomicInteger();

        @IdempotentMutation(ttl = "5s")
        public String makePayment(long amount) {
            int n = invocations.incrementAndGet();
            return "paid-" + amount + "-#" + n;
        }
    }

    @TestConfiguration
    static class ClockOverride {

        @Bean(name = "idempotencyClock")
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
