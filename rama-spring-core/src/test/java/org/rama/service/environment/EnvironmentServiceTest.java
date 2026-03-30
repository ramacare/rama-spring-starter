package org.rama.service.environment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {

    @Mock
    private Environment environment;

    @Mock
    private ObjectProvider<StaticValueResolver> staticValueResolverProvider;

    @Mock
    private StaticValueResolver staticValueResolver;

    @InjectMocks
    private EnvironmentService environmentService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUsername_shouldReturnAuthenticatedUsername() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("testUser", "password");
        SecurityContextHolder.getContext().setAuthentication(auth);

        String username = environmentService.getCurrentUsername();

        assertThat(username).isEqualTo("testUser");
    }

    @Test
    void getCurrentUsername_shouldReturnFallbackWhenNoAuthentication() {
        SecurityContextHolder.clearContext();
        when(staticValueResolverProvider.getIfAvailable()).thenReturn(staticValueResolver);
        when(staticValueResolver.getCurrentUsernameFallback()).thenReturn("systemUser");

        String username = environmentService.getCurrentUsername();

        assertThat(username).isEqualTo("systemUser");
    }

    @Test
    void getCurrentUsername_shouldReturnNullWhenNoAuthAndNoResolver() {
        SecurityContextHolder.clearContext();
        when(staticValueResolverProvider.getIfAvailable()).thenReturn(null);

        String username = environmentService.getCurrentUsername();

        assertThat(username).isNull();
    }

    @Test
    void isProfileActive_shouldReturnTrueWhenProfilePresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local", "dev"});

        assertThat(environmentService.isProfileActive("dev")).isTrue();
    }

    @Test
    void isProfileActive_shouldReturnFalseWhenProfileAbsent() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        assertThat(environmentService.isProfileActive("prod")).isFalse();
    }

    @Test
    void getStaticValue_shouldDelegateToResolver() {
        when(staticValueResolverProvider.getIfAvailable()).thenReturn(staticValueResolver);
        when(staticValueResolver.getStaticValue("app.name")).thenReturn("Rama");

        String value = environmentService.getStaticValue("app.name");

        assertThat(value).isEqualTo("Rama");
    }

    @Test
    void getStaticValue_shouldReturnNullWhenNoResolver() {
        when(staticValueResolverProvider.getIfAvailable()).thenReturn(null);

        String value = environmentService.getStaticValue("app.name");

        assertThat(value).isNull();
    }
}
