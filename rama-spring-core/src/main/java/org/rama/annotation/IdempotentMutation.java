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
     */
    String ttl() default "30s";
}
