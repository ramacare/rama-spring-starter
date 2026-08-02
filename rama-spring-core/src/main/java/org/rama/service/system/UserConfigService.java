package org.rama.service.system;

import org.rama.entity.system.UserConfig;
import org.rama.repository.system.UserConfigRepository;
import org.rama.service.environment.EnvironmentService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;

public class UserConfigService {
    private final UserConfigRepository userConfigRepository;
    private final EnvironmentService environmentService;

    public UserConfigService(UserConfigRepository userConfigRepository, EnvironmentService environmentService) {
        this.userConfigRepository = userConfigRepository;
        this.environmentService = environmentService;
    }

    /**
     * Returns the configuration for an application user, registering an empty one on first sight
     * so callers never have to handle a missing row on first login.
     */
    @Transactional
    public UserConfig retrieveOrRegister(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }

        UserConfig userConfig = userConfigRepository.findByUsername(username)
                .orElseGet(() -> {
                    UserConfig created = new UserConfig();
                    created.setUsername(username);
                    created.setConfiguration(new HashMap<>());
                    return created;
                });
        userConfig.setLastSeenDatetime(OffsetDateTime.now());
        return userConfigRepository.save(userConfig);
    }

    /**
     * The authenticated principal's configuration, or {@code null} when no principal can be
     * resolved -- an anonymous caller must not leave a row behind.
     */
    @Transactional
    public UserConfig retrieveOrRegisterCurrent() {
        String username = resolveCurrentUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return retrieveOrRegister(username);
    }

    /**
     * Anonymous access is not a principal. Spring Security populates the context with an
     * {@link AnonymousAuthenticationToken} whose name ({@code anonymousUser} by default) would
     * otherwise be registered as a real user. A null authentication is left to
     * {@link EnvironmentService}, which applies the configured system-user fallback -- that path
     * matters for jobs and other non-web callers.
     */
    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AnonymousAuthenticationToken || (authentication != null && !authentication.isAuthenticated())) {
            return null;
        }
        return environmentService.getCurrentUsername();
    }
}
