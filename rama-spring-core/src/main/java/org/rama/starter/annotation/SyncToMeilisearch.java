package org.rama.starter.annotation;

import org.rama.starter.meilisearch.mapper.DefaultMeilisearchMapper;
import org.rama.starter.meilisearch.mapper.IMeilisearchMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncToMeilisearch {
    String indexName() default "";
    String primaryKey() default "id";
    String[] searchableAttributes() default {};
    String[] filterableAttributes() default {};
    Class<? extends IMeilisearchMapper> mapperClass() default DefaultMeilisearchMapper.class;
}
