package org.rama.annotation;

import org.rama.mongo.mapper.IMongoMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncToMongo {
    Class<?> mongoClass();
    Class<? extends IMongoMapper<?, ?>> mapperClass();
}
