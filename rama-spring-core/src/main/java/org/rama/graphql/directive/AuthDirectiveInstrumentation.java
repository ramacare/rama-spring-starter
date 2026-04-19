package org.rama.graphql.directive;

import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
public class AuthDirectiveInstrumentation extends SimplePerformantInstrumentation {

    private static final String DIRECTIVE_NAME = "auth";

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
                                                 InstrumentationFieldFetchParameters parameters,
                                                 InstrumentationState state) {
        GraphQLFieldDefinition fieldDef = parameters.getEnvironment().getFieldDefinition();
        GraphQLAppliedDirective directive = fieldDef.getAppliedDirective(DIRECTIVE_NAME);
        if (directive == null) {
            return dataFetcher;
        }

        List<String> roles = extractRoles(directive);
        String match = extractMatch(directive);

        return environment -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                log.debug("Authentication required but user is not authenticated");
                throw new AuthenticationRequiredException("Authentication is required to access this resource");
            }

            if (!roles.isEmpty()) {
                Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
                boolean authorized;

                if ("ALL".equalsIgnoreCase(match)) {
                    authorized = roles.stream().allMatch(role -> hasAuthority(authorities, role));
                } else {
                    authorized = roles.stream().anyMatch(role -> hasAuthority(authorities, role));
                }

                if (!authorized) {
                    log.debug("Authorization denied for user '{}'. Required roles: {} (match: {})",
                            authentication.getName(), roles, match);
                    throw new AuthorizationDeniedException(
                            "Access denied. Required roles: " + roles + " (match: " + match + ")");
                }
            }

            return dataFetcher.get(environment);
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(GraphQLAppliedDirective directive) {
        GraphQLAppliedDirectiveArgument rolesArg = directive.getArgument("roles");
        if (rolesArg == null || rolesArg.getValue() == null) {
            return Collections.emptyList();
        }
        Object value = rolesArg.getValue();
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    private String extractMatch(GraphQLAppliedDirective directive) {
        GraphQLAppliedDirectiveArgument matchArg = directive.getArgument("match");
        if (matchArg == null || matchArg.getValue() == null) {
            return "ANY";
        }
        Object value = matchArg.getValue();
        return value instanceof String ? (String) value : "ANY";
    }

    private boolean hasAuthority(Collection<? extends GrantedAuthority> authorities, String role) {
        return authorities.stream().anyMatch(authority -> {
            String authorityName = authority.getAuthority();
            return authorityName.equalsIgnoreCase(role)
                    || authorityName.equalsIgnoreCase("ROLE_" + role);
        });
    }
}
