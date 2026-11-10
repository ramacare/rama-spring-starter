package org.rama.demo.idempotency;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
 *
 * <p>The TTL governs a call that carries an {@code Idempotency-Key}, so each test sends
 * one. A header-less call is held for {@code rama.idempotency.fallback-window} instead
 * (starter#67, {@link IdempotencyFallbackWindowIT}).
 */
@Tag("integration")
@SpringBootTest(properties = "rama.idempotency.default-ttl=7m")
@ActiveProfiles("h2")
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

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unqualifiedAnnotation_takesTtlFromDefaultTtlProperty() throws Exception {
        sendIdempotencyKey("ttl-1");
        mutation.unqualified(1L);

        SystemRequestDedup row = repository.findById(signatureOf("unqualified", 1L)).orElseThrow();
        assertThat(Duration.between(FIXED, row.getExpiresAt().toInstant()))
                .as("rama.idempotency.default-ttl=7m governs an @IdempotentMutation with no ttl")
                .isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void explicitTtl_stillOverridesTheProperty() throws Exception {
        sendIdempotencyKey("ttl-2");
        mutation.explicit(2L);

        SystemRequestDedup row = repository.findById(signatureOf("explicit", 2L)).orElseThrow();
        assertThat(Duration.between(FIXED, row.getExpiresAt().toInstant()))
                .as("a ttl on the annotation wins over the property")
                .isEqualTo(Duration.ofSeconds(5));
    }

    private static void sendIdempotencyKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", key);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private String signatureOf(String method, long id) throws NoSuchMethodException {
        return signatureResolver.resolve(TestMutation.class.getMethod(method, long.class), new Object[]{id}).signature();
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
