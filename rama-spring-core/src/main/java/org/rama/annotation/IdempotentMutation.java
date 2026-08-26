package org.rama.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IdempotentMutation {

    /**
     * Duration that the cached response stays valid. After this elapses the
     * Quartz cleanup job removes the row and identical requests run again.
     * Parsed by Spring's {@code Duration} support ("30s", "PT5M", etc.).
     *
     * <p>Left blank — the default — the TTL comes from
     * {@code rama.idempotency.default-ttl} (itself 30s unless configured), so a
     * deployment can retune every unqualified {@code @IdempotentMutation} from
     * one property. Set a value here only to override that method's TTL.
     * See starter#43.
     */
    String ttl() default "";
}
