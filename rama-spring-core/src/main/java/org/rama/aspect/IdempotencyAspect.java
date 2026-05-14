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
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class IdempotencyAspect {

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
        Type returnType = sig.getMethod().getGenericReturnType();

        String signature = signatureResolver.resolve(pjp.getArgs());
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration ttl = parseTtl(idempotent.ttl());
        OffsetDateTime expiresAt = now.plus(ttl);
        String methodName = sig.toLongString();
        String username = environmentService.getCurrentUsername();

        boolean claimed = idempotencyService.tryClaim(signature, methodName, username, now, expiresAt);
        if (log.isDebugEnabled()) {
            log.debug("idempotency claim signature={} method={} claimed={}", signature, methodName, claimed);
        }

        Throwable[] thrownByWork = new Throwable[1];
        try {
            return idempotencyService.lockAndExecute(signature, returnType, now, expiresAt, () -> {
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

    private Duration parseTtl(String spec) {
        if (spec == null || spec.isBlank()) return properties.getDefaultTtl();
        return DurationStyle.detectAndParse(spec);
    }

    private static final class WorkException extends RuntimeException {
        WorkException(Throwable cause) { super(cause); }
    }
}
