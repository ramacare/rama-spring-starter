package com.example.consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.aspect.IdempotencyAspect;
import org.rama.service.idempotency.IdempotencyService;
import org.rama.service.idempotency.SignatureResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring assertion for starter#46. The behavioural idempotency ITs all run in the
 * demo's own context and so never noticed that the auto-configuration could be
 * skipped outright — a skipped configuration produces no error, no warning and no
 * log line, only a permanently empty {@code system_request_dedup}.
 *
 * <p>This asserts the beans exist at all, from an application root outside
 * {@code org.rama}, which is the shape the failure was reported against.
 */
@Tag("integration")
@SpringBootTest(classes = ConsumerShapedApplication.class)
class IdempotencyWiringIT {

    @Autowired ApplicationContext context;

    @Test
    void idempotencyBeans_areRegisteredInAConsumerShapedContext() {
        assertThat(context.getBeansOfType(IdempotencyAspect.class))
                .as("@IdempotentMutation is inert without the aspect")
                .isNotEmpty();
        assertThat(context.getBeansOfType(IdempotencyService.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SignatureResolver.class)).isNotEmpty();
    }

    @Test
    void idempotencyService_resolvedItsRepository() {
        assertThat(context.getBean(IdempotencyService.class).isAvailable())
                .as("repository resolved, so claims actually reach system_request_dedup")
                .isTrue();
    }
}
