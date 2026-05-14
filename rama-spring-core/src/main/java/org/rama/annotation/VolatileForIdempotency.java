package org.rama.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field (or getter) on an input DTO as volatile for idempotency
 * canonicalisation. Annotated members are stripped before hashing, so
 * two requests that differ only by these fields produce the same
 * signature.
 *
 * Use for client-generated correlation values (request datetimes, trace
 * ids) that shouldn't affect the dedup outcome.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface VolatileForIdempotency {
}
