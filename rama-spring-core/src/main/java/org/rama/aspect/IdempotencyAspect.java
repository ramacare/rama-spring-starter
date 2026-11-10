package org.rama.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.rama.annotation.IdempotentMutation;
import org.rama.service.environment.EnvironmentService;
import org.rama.service.idempotency.IdempotencyProperties;
import org.rama.service.idempotency.IdempotencyService;
import org.rama.service.idempotency.SignatureResolver;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Implements {@link IdempotentMutation}. See issue #23 for the full state machine.
 *
 * Two transactions per invocation:
 *   1. {@link IdempotencyService#tryClaim}    — atomic-or-noop slot claim
 *   2. {@link IdempotencyService#lockAndExecute} — locks the row, branches on
 *      status, runs the underlying mutation (joining this transaction) if needed,
 *      writes the response back, commits.
 *
 * Ordered via {@code rama.idempotency.aspect-order}. Left unordered it tied with
 * every consumer aspect at {@code LOWEST_PRECEDENCE}; when a consumer's
 * {@code @AfterReturning} aspect won the tie it wrapped this one and re-fired its
 * side effect on every replay. See starter#64.
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class IdempotencyAspect implements Ordered {

    private final IdempotencyService idempotencyService;
    private final SignatureResolver signatureResolver;
    private final EnvironmentService environmentService;
    private final IdempotencyProperties properties;
    private final Clock clock;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, IdempotentMutation idempotent) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Type returnType = method.getGenericReturnType();

        SignatureResolver.Resolution resolution = signatureResolver.resolve(method, pjp.getArgs());
        Duration window = resolution.keyed()
                ? parseDuration(idempotent.ttl(), properties.getDefaultTtl())
                : parseDuration(idempotent.fallbackWindow(), properties.getFallbackWindow());
        if (!resolution.keyed() && (window.isZero() || window.isNegative())) {
            // fallbackWindow = "0s": identical header-less calls are meant to run. See starter#67.
            return pjp.proceed();
        }

        String signature = resolution.signature();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String methodName = sig.toLongString();
        String username = environmentService.getCurrentUsername();

        boolean claimed = idempotencyService.tryClaim(signature, methodName, username, now, now.plus(window));
        if (log.isDebugEnabled()) {
            log.debug("idempotency claim signature={} method={} keyed={} claimed={}",
                    signature, methodName, resolution.keyed(), claimed);
        }

        Throwable[] thrownByWork = new Throwable[1];
        try {
            return idempotencyService.lockAndExecute(signature, returnType, now, window, () -> {
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    thrownByWork[0] = t;
                    if (t instanceof RuntimeException re) throw re;
                    if (t instanceof Error err) throw err;
                    throw new WorkException(t);
                }
            });
        } catch (Throwable outer) {
            if (thrownByWork[0] != null) throw thrownByWork[0];
            throw outer;
        }
    }

    @Override
    public int getOrder() {
        return properties.getAspectOrder();
    }

    private static Duration parseDuration(String spec, Duration fallback) {
        if (spec == null || spec.isBlank()) return fallback;
        return DurationStyle.detectAndParse(spec);
    }

    private static final class WorkException extends RuntimeException {
        WorkException(Throwable cause) { super(cause); }
    }
}
