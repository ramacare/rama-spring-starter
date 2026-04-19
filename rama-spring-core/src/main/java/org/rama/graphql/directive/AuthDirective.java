package org.rama.graphql.directive;

import graphql.schema.DataFetcher;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.idl.SchemaDirectiveWiring;
import graphql.schema.idl.SchemaDirectiveWiringEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
public class AuthDirective implements SchemaDirectiveWiring {

    private static final String DIRECTIVE_NAME = "auth";

    @Override
    public GraphQLFieldDefinition onField(SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> environment) {
        GraphQLFieldDefinition field = environment.getFieldDefinition();
        GraphQLAppliedDirective directive = field.getAppliedDirective(DIRECTIVE_NAME);
        if (directive == null) {
            return field;
        }
        GraphQLObjectType parentType = (GraphQLObjectType) environment.getFieldsContainer();

        List<String> roles = extractRoles(directive);
        String match = extractMatch(directive);

        DataFetcher<?> originalFetcher = environment.getCodeRegistry()
                .getDataFetcher(parentType, field);

        DataFetcher<?> wrappedFetcher = createAuthFetcher(originalFetcher, roles, match);

        environment.getCodeRegistry()
                .dataFetcher(parentType, field, wrappedFetcher);

        return field;
    }

    @Override
    public GraphQLObjectType onObject(SchemaDirectiveWiringEnvironment<GraphQLObjectType> environment) {
        GraphQLObjectType objectType = environment.getElement();
        GraphQLAppliedDirective directive = objectType.getAppliedDirective(DIRECTIVE_NAME);
        if (directive == null) {
            return objectType;
        }

        List<String> roles = extractRoles(directive);
        String match = extractMatch(directive);

        for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
            DataFetcher<?> originalFetcher = environment.getCodeRegistry()
                    .getDataFetcher(objectType, field);

            DataFetcher<?> wrappedFetcher = createAuthFetcher(originalFetcher, roles, match);

            environment.getCodeRegistry()
                    .dataFetcher(objectType, field, wrappedFetcher);
        }

        return objectType;
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

    private DataFetcher<?> createAuthFetcher(DataFetcher<?> originalFetcher, List<String> roles, String match) {
        return dataFetchingEnvironment -> {
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

            return originalFetcher.get(dataFetchingEnvironment);
        };
    }

    private boolean hasAuthority(Collection<? extends GrantedAuthority> authorities, String role) {
        return authorities.stream().anyMatch(authority -> {
            String authorityName = authority.getAuthority();
            return authorityName.equalsIgnoreCase(role)
                    || authorityName.equalsIgnoreCase("ROLE_" + role);
        });
    }
}
