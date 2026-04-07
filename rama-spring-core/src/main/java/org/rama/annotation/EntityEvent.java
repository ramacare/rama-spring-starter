package org.rama.annotation;

import org.rama.event.EntityCreated;
import org.rama.event.EntityUpdated;
import org.rama.event.IEntityEvent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityEvent {
    Class<? extends IEntityEvent> createdEvent() default EntityCreated.class;
    Class<? extends IEntityEvent> updatedEvent() default EntityUpdated.class;
    boolean afterCommit() default true;
}
