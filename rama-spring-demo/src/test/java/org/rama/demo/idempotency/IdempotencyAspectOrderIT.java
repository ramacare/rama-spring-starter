package org.rama.demo.idempotency;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.IdempotentMutation;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for starter#64, part 2. {@code IdempotencyAspect} had no order, so it
 * tied with every consumer aspect at {@code LOWEST_PRECEDENCE}. A consumer
 * {@code @AfterReturning} aspect that won the tie wrapped it, saw the replayed return
 * value, and re-fired its side effect on every duplicate — ramaservice's
 * {@code EncounterSyncAspect} pushed the same encounter to legacy twice.
 *
 * <p>The probe aspect here is deliberately left unordered, like a typical consumer aspect.
 */
@Tag("integration")
@SpringBootTest
@Import({IdempotencyAspectOrderIT.Mutation.class, IdempotencyAspectOrderIT.SideEffectAspect.class,
        IdempotencyAspectOrderIT.ClockOverride.class})
class IdempotencyAspectOrderIT {

    private static final Instant FIXED = Instant.parse("2026-05-15T00:00:00Z");

    @Autowired Mutation mutation;
    @Autowired SystemRequestDedupRepository dedupRepository;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx.execute(s -> {
            dedupRepository.deleteAll();
            return null;
        });
        Mutation.invocations.set(0);
        SideEffectAspect.fired.set(0);
    }

    @Test
    void unorderedConsumerAspect_doesNotFireOnReplay() {
        String first = mutation.create(42L);
        String replay = mutation.create(42L);

        assertThat(replay).isEqualTo(first);
        assertThat(Mutation.invocations).hasValue(1);
        assertThat(SideEffectAspect.fired).as("side effect must not re-fire on the replay").hasValue(1);
    }

    @Component
    static class Mutation {

        static final AtomicInteger invocations = new AtomicInteger();

        @IdempotentMutation
        public String create(long id) {
            return "created-" + id + "-#" + invocations.incrementAndGet();
        }
    }

    @Aspect
    @Component
    static class SideEffectAspect {

        static final AtomicInteger fired = new AtomicInteger();

        @AfterReturning("execution(* org.rama.demo.idempotency.IdempotencyAspectOrderIT.Mutation.create(..))")
        public void afterCreate() {
            fired.incrementAndGet();
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
