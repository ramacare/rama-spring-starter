package org.rama.starter.service.environment;

import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

public class EnvironmentService {
    private final Environment environment;
    private final StaticValueResolver staticValueResolver;

    public EnvironmentService(Environment environment, StaticValueResolver staticValueResolver) {
        this.environment = environment;
        this.staticValueResolver = staticValueResolver;
    }

    public List<String> getActiveProfile() {
        return Arrays.stream(environment.getActiveProfiles()).toList();
    }

    public Boolean isProfileActive(String profile) {
        return getActiveProfile().contains(profile);
    }

    public Boolean isProfileActive(List<String> profiles) {
        return profiles.stream().anyMatch(getActiveProfile()::contains);
    }

    public Boolean isProfileActive(String[] profiles) {
        return Arrays.stream(profiles).anyMatch(getActiveProfile()::contains);
    }

    public String getStaticValue(String key) {
        return staticValueResolver == null ? null : staticValueResolver.getStaticValue(key);
    }

    public StaticValueResolver getStaticValueService() {
        return staticValueResolver;
    }

    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication != null ? authentication.getName() : null;
        if (username == null && staticValueResolver != null) {
            username = staticValueResolver.getCurrentUsernameFallback();
        }
        return username;
    }
}
