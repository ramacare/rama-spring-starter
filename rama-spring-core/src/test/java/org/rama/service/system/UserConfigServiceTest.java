package org.rama.service.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.UserConfig;
import org.rama.repository.system.UserConfigRepository;
import org.rama.service.environment.EnvironmentService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserConfigServiceTest {

    @Mock
    private UserConfigRepository userConfigRepository;

    @Mock
    private EnvironmentService environmentService;

    @InjectMocks
    private UserConfigService userConfigService;

    @Captor
    private ArgumentCaptor<UserConfig> userConfigCaptor;

    @Test
    void retrieveOrRegister_shouldReturnExistingByUsername() {
        UserConfig existing = new UserConfig();
        existing.setId(7L);
        existing.setUsername("somchai");

        when(userConfigRepository.findByUsername("somchai")).thenReturn(Optional.of(existing));
        when(userConfigRepository.save(any(UserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        UserConfig result = userConfigService.retrieveOrRegister("somchai");

        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getLastSeenDatetime()).isNotNull();
    }

    @Test
    void retrieveOrRegister_shouldCreateNewWhenNotFound() {
        when(userConfigRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(userConfigRepository.save(any(UserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        userConfigService.retrieveOrRegister("newuser");

        verify(userConfigRepository).save(userConfigCaptor.capture());
        UserConfig saved = userConfigCaptor.getValue();
        assertThat(saved.getUsername()).isEqualTo("newuser");
        assertThat(saved.getLastSeenDatetime()).isNotNull();
        assertThat(saved.getConfiguration()).isNotNull().isEmpty();
    }

    @Test
    void retrieveOrRegister_shouldThrowWhenUsernameBlank() {
        assertThatThrownBy(() -> userConfigService.retrieveOrRegister(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");

        verify(userConfigRepository, never()).save(any());
    }

    @Test
    void retrieveOrRegisterCurrent_shouldResolveThePrincipal() {
        when(environmentService.getCurrentUsername()).thenReturn("somchai");
        when(userConfigRepository.findByUsername("somchai")).thenReturn(Optional.empty());
        when(userConfigRepository.save(any(UserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        UserConfig result = userConfigService.retrieveOrRegisterCurrent();

        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo("somchai");
    }

    /** An anonymous caller must not leave a row behind. */
    @Test
    void retrieveOrRegisterCurrent_shouldReturnNullWhenNoPrincipalResolved() {
        when(environmentService.getCurrentUsername()).thenReturn(null);

        assertThat(userConfigService.retrieveOrRegisterCurrent()).isNull();

        verify(userConfigRepository, never()).save(any());
    }

    @Test
    void retrieveOrRegisterCurrent_shouldReturnNullWhenPrincipalIsBlank() {
        when(environmentService.getCurrentUsername()).thenReturn("   ");

        assertThat(userConfigService.retrieveOrRegisterCurrent()).isNull();

        verify(userConfigRepository, never()).save(any());
    }

    /**
     * Spring Security populates an anonymous token whose name is {@code anonymousUser}; taking it
     * at face value would register that as a real user on every unauthenticated call.
     */
    @Test
    void retrieveOrRegisterCurrent_shouldReturnNullForAnonymousAuthentication() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        SecurityContextHolder.setContext(context);
        try {
            assertThat(userConfigService.retrieveOrRegisterCurrent()).isNull();
            verify(userConfigRepository, never()).save(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void retrieveOrRegisterCurrent_shouldUseAnAuthenticatedPrincipal() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "demo-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        try {
            when(environmentService.getCurrentUsername()).thenReturn("demo-user");
            when(userConfigRepository.findByUsername("demo-user")).thenReturn(Optional.empty());
            when(userConfigRepository.save(any(UserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(userConfigService.retrieveOrRegisterCurrent().getUsername()).isEqualTo("demo-user");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
