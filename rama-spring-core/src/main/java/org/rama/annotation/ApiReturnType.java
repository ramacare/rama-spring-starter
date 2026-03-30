package org.rama.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ApiReturnTypes.class)
public @interface ApiReturnType {
    String apiId() default "";
    Class<?> rawType();
    Class<?>[] actualTypeArguments() default {};
}
