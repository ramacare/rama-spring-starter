package org.rama.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.rama.security.ApiKeyService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-key filter for the GraphQL endpoint. The starter's {@link org.rama.security.ApiKeyAuthFilter}
 * only processes {@code /api/**} paths; this filter covers {@code /graphql} so the
 * {@code @auth} directive can read the authenticated principal from SecurityContext.
 */
@RequiredArgsConstructor
public class GraphQlApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-KEY";
    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                && !(SecurityContextHolder.getContext().getAuthentication()
                        instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (!StringUtils.hasText(apiKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        var result = apiKeyService.authenticate(apiKey);
        if (!result.valid()) {
            filterChain.doFilter(request, response);
            return;
        }

        Set<SimpleGrantedAuthority> authorities = result.roles().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        var auth = new UsernamePasswordAuthenticationToken(result.username(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        apiKeyService.markUsed(apiKey);

        filterChain.doFilter(request, response);
    }
}
