package org.rama.demo.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.IdempotentMutation;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter#67. A caller that sends no {@code Idempotency-Key} header was deduplicated only
 * within one wall-clock second: the signature started with {@code floor(now, 1s)}, so a
 * double-submit straddling a second boundary, a re-click after a slow response, or a
 * client retry all ran the mutation again. The method was not hashed, and volatile keys
 * in a {@code Map} input were not stripped.
 *
 * <p>The clock starts at {@code .900} on purpose: under the old second bucket, even the
 * 200 ms step in these tests would have produced a fresh signature.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "rama.idempotency.fallback-window=4s",
        "rama.idempotency.volatile-fields=clientStamp"})
@Import({IdempotencyFallbackWindowIT.Mutations.class, IdempotencyFallbackWindowIT.ClockOverride.class})
class IdempotencyFallbackWindowIT {

    static final Instant T0 = Instant.parse("2026-05-15T00:00:00.900Z");

    @Autowired Mutations mutations;
    @Autowired MutableClock clock;
    @Autowired SystemRequestDedupRepository repository;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx.execute(s -> {
            repository.deleteAll();
            return null;
        });
        Mutations.invocations.set(0);
        clock.set(T0);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void identicalCallAcrossASecondBoundary_replays() {
        String first = mutations.create("boundary");
        clock.advance(Duration.ofMillis(200));
        String second = mutations.create("boundary");

        assertThat(Mutations.invocations).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void identicalCallTwoSecondsLater_replays() {
        String first = mutations.create("retry");
        clock.advance(Duration.ofSeconds(2));
        String second = mutations.create("retry");

        assertThat(Mutations.invocations).hasValue(1);
        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1L);
    }

    @Test
    void identicalCallAfterTheWindow_runsAgain() {
        String first = mutations.create("later");
        clock.advance(Duration.ofSeconds(5));
        String second = mutations.create("later");

        assertThat(Mutations.invocations).hasValue(2);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void windowIsCountedFromCompletion_soASlowCallStillReplays() {
        mutations.slowCreate("slow");          // the work itself takes 10 s of clock time
        clock.advance(Duration.ofSeconds(2));  // the duplicate lands 2 s after it finished
        mutations.slowCreate("slow");

        assertThat(Mutations.invocations).hasValue(1);
        SystemRequestDedup row = repository.findAll().get(0);
        assertThat(row.getExpiresAt().toInstant()).isEqualTo(T0.plusSeconds(10).plusSeconds(4));
    }

    @Test
    void differentMethodsWithEqualArgs_bothRun() {
        String created = mutations.create("same");
        String archived = mutations.archive("same");

        assertThat(Mutations.invocations).hasValue(2);
        assertThat(created).startsWith("create-");
        assertThat(archived).startsWith("archive-");
    }

    @Test
    void mapInputDifferingOnlyByVolatileKeys_replays() {
        String first = mutations.createFromInput(input("2026-05-15T00:00:00Z", "trace-1", 1));
        clock.advance(Duration.ofSeconds(1));
        String second = mutations.createFromInput(input("2026-05-15T00:00:01Z", "trace-2", 2));

        assertThat(Mutations.invocations).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void fallbackWindowProperty_setsTheRowExpiry() {
        mutations.create("expiry");

        assertThat(repository.findAll().get(0).getExpiresAt().toInstant()).isEqualTo(T0.plusSeconds(4));
    }

    @Test
    void annotationFallbackWindow_overridesTheProperty() {
        mutations.shortWindow("short");

        assertThat(repository.findAll().get(0).getExpiresAt().toInstant()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void zeroFallbackWindow_optsTheMethodOutOfHeaderlessDedup() {
        mutations.optedOut("toggle");
        mutations.optedOut("toggle");

        assertThat(Mutations.invocations).hasValue(2);
        assertThat(repository.count()).isZero();
    }

    @Test
    void keyedCall_usesTtl_andIgnoresTheFallbackWindow() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "client-key-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        mutations.optedOut("keyed");
        mutations.optedOut("keyed");

        assertThat(Mutations.invocations).as("a header still deduplicates an opted-out method").hasValue(1);
        assertThat(repository.findAll().get(0).getExpiresAt().toInstant()).isEqualTo(T0.plusSeconds(30));
    }

    private static Map<String, Object> input(String requestDatetime, String traceId, int clientStamp) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("qty", 2);
        line.put("traceId", traceId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "order-1");
        input.put("requestDatetime", requestDatetime);
        input.put("traceId", traceId);
        input.put("clientStamp", clientStamp);
        input.put("lines", List.of(line));
        return input;
    }

    @Component
    static class Mutations {

        static final AtomicInteger invocations = new AtomicInteger();

        @Autowired MutableClock clock;

        @IdempotentMutation(ttl = "30s")
        public String create(String name) {
            return "create-" + name + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation(ttl = "30s")
        public String archive(String name) {
            return "archive-" + name + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation
        public String slowCreate(String name) {
            clock.advance(Duration.ofSeconds(10));
            return "slow-" + name + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation
        public String createFromInput(Map<String, Object> input) {
            return "input-" + input.get("name") + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation(fallbackWindow = "1s")
        public String shortWindow(String name) {
            return "short-" + name + "-#" + invocations.incrementAndGet();
        }

        @IdempotentMutation(ttl = "30s", fallbackWindow = "0s")
        public String optedOut(String name) {
            return "opted-out-" + name + "-#" + invocations.incrementAndGet();
        }
    }

    @TestConfiguration
    static class ClockOverride {

        @Bean(name = "idempotencyClock")
        @Primary
        MutableClock idempotencyClock() {
            return new MutableClock(T0);
        }
    }
}
