package org.rama.starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ApiReturnTypes.class)
public @interface ApiReturnType {
    String apiId() default "";
    Class<?> rawType();
    Class<?>[] actualTypeArguments() default {};
}
