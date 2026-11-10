package org.rama.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IdempotentMutation {

    /**
     * How long the cached response of a call that carried the idempotency header
     * ({@code rama.idempotency.header-name}) stays valid, counted from when the call
     * finished. After this elapses a repeat of the same key runs again, and the Quartz
     * cleanup job removes the row. Parsed by Spring's {@code Duration} support
     * ("30s", "PT5M", etc.).
     *
     * <p>Left blank — the default — the TTL comes from
     * {@code rama.idempotency.default-ttl} (itself 30s unless configured), so a
     * deployment can retune every unqualified {@code @IdempotentMutation} from
     * one property. Set a value here only to override that method's TTL.
     * See starter#43.
     */
    String ttl() default "";

    /**
     * How long a call that carried <em>no</em> idempotency header is replayed, counted
     * from when it finished. Such a call is matched by method, user and arguments, so an
     * identical repeat inside the window — a double-submit, a retry, a re-click after a
     * slow response — returns the first call's response instead of running again.
     *
     * <p>Left blank — the default — it comes from {@code rama.idempotency.fallback-window}
     * (5s unless configured). Set {@code "0s"} on a method whose identical repeats are
     * meant to run, such as a toggle; a caller that sends the header is still
     * deduplicated by {@link #ttl()}. See starter#67.
     */
    String fallbackWindow() default "";
}
