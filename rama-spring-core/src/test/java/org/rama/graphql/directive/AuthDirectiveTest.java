package org.rama.graphql.directive;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.idl.SchemaDirectiveWiringEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AuthDirectiveTest {

    private AuthDirective authDirective;

    @Mock
    private DataFetcher<Object> originalFetcher;

    @Mock
    private DataFetchingEnvironment dataFetchingEnvironment;

    @BeforeEach
    void setUp() {
        authDirective = new AuthDirective();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onField_whenNotAuthenticated_shouldThrowAuthenticationRequired() throws Exception {
        // No authentication set in SecurityContext
        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of(), "ANY");

        assertThatThrownBy(() -> wrappedFetcher.get(dataFetchingEnvironment))
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessageContaining("Authentication is required");
    }

    @Test
    void onField_whenAnonymousUser_shouldThrowAuthenticationRequired() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        );

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of(), "ANY");

        assertThatThrownBy(() -> wrappedFetcher.get(dataFetchingEnvironment))
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessageContaining("Authentication is required");
    }

    @Test
    void onField_whenAuthenticatedNoRoles_shouldCallOriginalFetcher() throws Exception {
        setAuthenticated("user", "ROLE_USER");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of(), "ANY");
        Object result = wrappedFetcher.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
        verify(originalFetcher).get(dataFetchingEnvironment);
    }

    @Test
    void onField_whenHasMatchingRoleAny_shouldCallOriginalFetcher() throws Exception {
        setAuthenticated("user", "ROLE_DOCTOR");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of("ADMIN", "DOCTOR"), "ANY");
        Object result = wrappedFetcher.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
        verify(originalFetcher).get(dataFetchingEnvironment);
    }

    @Test
    void onField_whenMissingRoleAny_shouldThrowAuthorizationDenied() throws Exception {
        setAuthenticated("user", "ROLE_DOCTOR");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of("ADMIN"), "ANY");

        assertThatThrownBy(() -> wrappedFetcher.get(dataFetchingEnvironment))
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void onField_whenHasAllRolesMatchAll_shouldCallOriginalFetcher() throws Exception {
        setAuthenticated("user", "ROLE_ADMIN", "ROLE_DOCTOR");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of("ADMIN", "DOCTOR"), "ALL");
        Object result = wrappedFetcher.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
        verify(originalFetcher).get(dataFetchingEnvironment);
    }

    @Test
    void onField_whenMissingOneRoleMatchAll_shouldThrowAuthorizationDenied() throws Exception {
        setAuthenticated("user", "ROLE_DOCTOR");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of("ADMIN", "DOCTOR"), "ALL");

        assertThatThrownBy(() -> wrappedFetcher.get(dataFetchingEnvironment))
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void onField_whenRoleWithPrefix_shouldMatch() throws Exception {
        setAuthenticated("user", "ROLE_ADMIN");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        DataFetcher<?> wrappedFetcher = wrapFetcher(List.of("ADMIN"), "ANY");
        Object result = wrappedFetcher.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
        verify(originalFetcher).get(dataFetchingEnvironment);
    }

    // --- helpers ---

    private void setAuthenticated(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "password", authorities)
        );
    }

    @SuppressWarnings("unchecked")
    private DataFetcher<?> wrapFetcher(List<String> roles, String match) {
        SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> environment =
                mock(SchemaDirectiveWiringEnvironment.class);

        GraphQLFieldDefinition fieldDefinition = GraphQLFieldDefinition.newFieldDefinition()
                .name("testField")
                .type(graphql.Scalars.GraphQLString)
                .build();

        GraphQLObjectType parentType = GraphQLObjectType.newObject()
                .name("TestType")
                .field(fieldDefinition)
                .build();

        // Rebuild field from parentType so it's the same instance
        fieldDefinition = parentType.getFieldDefinition("testField");

        GraphQLAppliedDirective directive = mock(GraphQLAppliedDirective.class);

        // Set up roles argument
        if (roles.isEmpty()) {
            when(directive.getArgument("roles")).thenReturn(null);
        } else {
            GraphQLAppliedDirectiveArgument rolesArg = mock(GraphQLAppliedDirectiveArgument.class);
            when(rolesArg.getValue()).thenReturn(roles);
            when(directive.getArgument("roles")).thenReturn(rolesArg);
        }

        // Set up match argument (lenient because not all paths read the match argument)
        GraphQLAppliedDirectiveArgument matchArg = mock(GraphQLAppliedDirectiveArgument.class);
        org.mockito.Mockito.lenient().when(matchArg.getValue()).thenReturn(match);
        org.mockito.Mockito.lenient().when(directive.getArgument("match")).thenReturn(matchArg);

        GraphQLCodeRegistry.Builder codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry();
        codeRegistryBuilder.dataFetcher(parentType, fieldDefinition, originalFetcher);

        when(environment.getFieldDefinition()).thenReturn(fieldDefinition);
        when(environment.getAppliedDirective()).thenReturn(directive);
        when(environment.getFieldsContainer()).thenReturn(parentType);
        when(environment.getCodeRegistry()).thenReturn(codeRegistryBuilder);

        authDirective.onField(environment);

        // Extract the wrapped fetcher from the code registry
        GraphQLCodeRegistry registry = codeRegistryBuilder.build();
        return registry.getDataFetcher(parentType, fieldDefinition);
    }
}
