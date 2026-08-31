package org.rama.demo.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.IdempotentMutation;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.rama.service.idempotency.SignatureResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for starter#43. {@code rama.idempotency.default-ttl} used to be
 * unreachable: the annotation's own default was the literal {@code "30s"}, so the
 * aspect's fall-through to the property only fired for someone who wrote
 * {@code @IdempotentMutation(ttl = "")} on purpose. Setting the property changed
 * nothing for any normally-annotated method.
 *
 * <p>The annotation now defaults to blank, so an unqualified
 * {@code @IdempotentMutation} takes its TTL from the property.
 */
@Tag("integration")
@SpringBootTest(properties = "rama.idempotency.default-ttl=7m")
@Import({IdempotencyDefaultTtlIT.TestMutation.class, IdempotencyDefaultTtlIT.ClockOverride.class})
class IdempotencyDefaultTtlIT {

    private static final Instant FIXED = Instant.parse("2026-05-15T00:00:00Z");

    @Autowired TestMutation mutation;
    @Autowired SystemRequestDedupRepository repository;
    @Autowired SignatureResolver signatureResolver;
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
    void unqualifiedAnnotation_takesTtlFromDefaultTtlProperty() {
        mutation.unqualified(1L);

        SystemRequestDedup row = repository.findById(signatureResolver.resolve(new Object[]{1L})).orElseThrow();
        assertThat(Duration.between(FIXED, row.getExpiresAt().toInstant()))
                .as("rama.idempotency.default-ttl=7m governs an @IdempotentMutation with no ttl")
                .isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void explicitTtl_stillOverridesTheProperty() {
        mutation.explicit(2L);

        SystemRequestDedup row = repository.findById(signatureResolver.resolve(new Object[]{2L})).orElseThrow();
        assertThat(Duration.between(FIXED, row.getExpiresAt().toInstant()))
                .as("a ttl on the annotation wins over the property")
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Component
    static class TestMutation {

        static final AtomicInteger invocations = new AtomicInteger();

        @IdempotentMutation
        public String unqualified(long id) {
            return "unqualified-" + id + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation(ttl = "5s")
        public String explicit(long id) {
            return "explicit-" + id + "-#" + invocations.incrementAndGet();
        }
    }

    @TestConfiguration
    static class ClockOverride {

        @Bean(name = "idempotencyClock")
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }
}
