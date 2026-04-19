package org.rama.demo.security;

import lombok.RequiredArgsConstructor;
import org.rama.security.ApiKeyAuthFilter;
import org.rama.security.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class DemoSecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final ApiKeyService apiKeyService;

    @Bean
    SecurityFilterChain demoFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/graphiql/**", "/graphql").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new GraphQlApiKeyFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                .anonymous(Customizer.withDefaults());
        return http.build();
    }
}
