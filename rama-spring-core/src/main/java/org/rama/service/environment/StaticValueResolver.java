package org.rama.service.environment;

public interface StaticValueResolver {
    String getStaticValue(String key);

    default String getCurrentUsernameFallback() {
        return null;
    }
}
