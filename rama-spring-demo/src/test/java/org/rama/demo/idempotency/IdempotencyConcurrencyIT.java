package org.rama.demo.idempotency;

import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@Import({IdempotencyConcurrencyIT.TestMutation.class, IdempotencyConcurrencyIT.ClockOverride.class})
class IdempotencyConcurrencyIT {

    @Autowired TestMutation mutation;
    @Autowired SystemRequestDedupRepository repository;
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
    void twoThreads_sameArgs_singleUnderlyingExecution() throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<String> a = new AtomicReference<>();
        AtomicReference<String> b = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();

        Runnable call = () -> {
            try {
                ready.countDown();
                fire.await(5, TimeUnit.SECONDS);
                String r = mutation.makePayment(42L, "INV-1");
                if (a.get() == null) a.set(r); else b.set(r);
            } catch (Throwable t) {
                err.set(t);
            }
        };
        pool.submit(call);
        pool.submit(call);

        ready.await(5, TimeUnit.SECONDS);
        fire.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).as("threads completed").isTrue();
        assertThat(err.get()).as("no thread threw").isNull();

        assertThat(TestMutation.invocations.get())
                .as("exactly one underlying execution")
                .isEqualTo(1);
        assertThat(a.get())
                .as("both threads observe same response")
                .isNotNull()
                .isEqualTo(b.get());
        assertThat(repository.count()).isEqualTo(1L);
    }

    @Slf4j
    @Component
    static class TestMutation {

        static final AtomicInteger invocations = new AtomicInteger();

        @IdempotentMutation(ttl = "5s")
        public String makePayment(long amount, String invoiceId) {
            int n = invocations.incrementAndGet();
            log.info("makePayment invocation #{} for {} amount={}", n, invoiceId, amount);
            return "paid-" + invoiceId + "-#" + n;
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
