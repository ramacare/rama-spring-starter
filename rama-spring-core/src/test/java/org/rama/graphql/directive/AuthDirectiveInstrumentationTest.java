package org.rama.graphql.directive;

import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AuthDirectiveInstrumentationTest {

    private AuthDirectiveInstrumentation instrumentation;

    @Mock
    private DataFetcher<Object> originalFetcher;

    @Mock
    private DataFetchingEnvironment dataFetchingEnvironment;

    @Mock
    private InstrumentationState instrumentationState;

    @BeforeEach
    void setUp() {
        instrumentation = new AuthDirectiveInstrumentation();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void instrumentDataFetcher_whenNoAuthDirective_shouldReturnOriginalFetcher() {
        GraphQLFieldDefinition field = GraphQLFieldDefinition.newFieldDefinition()
                .name("testField")
                .type(graphql.Scalars.GraphQLString)
                .build();

        InstrumentationFieldFetchParameters params = mockParams(field);
        DataFetcher<?> result = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

        assertThat(result).isSameAs(originalFetcher);
    }

    @Test
    void instrumentDataFetcher_whenNotAuthenticated_shouldThrowAuthenticationRequired() {
        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

        assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                .isInstanceOf(AuthenticationRequiredException.class)
                .hasMessageContaining("Authentication is required");
    }

    @Test
    void instrumentDataFetcher_whenAnonymous_shouldThrowAuthenticationRequired() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        );

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of(), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

        assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void instrumentDataFetcher_whenAuthenticatedNoRoles_shouldCallOriginal() throws Exception {
        setAuthenticated("user", "ROLE_USER");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of(), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
        Object result = wrapped.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
    }

    @Test
    void instrumentDataFetcher_whenHasMatchingRole_shouldCallOriginal() throws Exception {
        setAuthenticated("admin", "ROLE_ADMIN");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
        Object result = wrapped.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
    }

    @Test
    void instrumentDataFetcher_whenMissingRole_shouldThrowAuthorizationDenied() {
        setAuthenticated("user", "ROLE_USER");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

        assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void instrumentDataFetcher_whenMatchAll_andHasAllRoles_shouldCallOriginal() throws Exception {
        setAuthenticated("admin", "ROLE_ADMIN", "ROLE_DOCTOR");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
        Object result = wrapped.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
    }

    @Test
    void instrumentDataFetcher_whenMatchAll_andMissingOneRole_shouldThrowAuthorizationDenied() {
        setAuthenticated("user", "ROLE_DOCTOR");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

        assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void instrumentDataFetcher_whenRoleWithoutPrefix_shouldMatchPrefixed() throws Exception {
        setAuthenticated("admin", "ROLE_ADMIN");
        when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

        GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ADMIN"), "ANY");
        InstrumentationFieldFetchParameters params = mockParams(field);

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
        Object result = wrapped.get(dataFetchingEnvironment);

        assertThat(result).isEqualTo("result");
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

    private GraphQLFieldDefinition buildFieldWithAuth(List<String> roles, String match) {
        GraphQLAppliedDirective.Builder directiveBuilder = GraphQLAppliedDirective.newDirective()
                .name("auth");

        if (!roles.isEmpty()) {
            directiveBuilder.argument(GraphQLAppliedDirectiveArgument.newArgument()
                    .name("roles")
                    .type(graphql.schema.GraphQLList.list(graphql.Scalars.GraphQLString))
                    .valueProgrammatic(roles)
                    .build());
        }
        directiveBuilder.argument(GraphQLAppliedDirectiveArgument.newArgument()
                .name("match")
                .type(graphql.Scalars.GraphQLString)
                .valueProgrammatic(match)
                .build());

        return GraphQLFieldDefinition.newFieldDefinition()
                .name("testField")
                .type(graphql.Scalars.GraphQLString)
                .withAppliedDirective(directiveBuilder.build())
                .build();
    }

    private InstrumentationFieldFetchParameters mockParams(GraphQLFieldDefinition field) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getFieldDefinition()).thenReturn(field);

        InstrumentationFieldFetchParameters params = mock(InstrumentationFieldFetchParameters.class);
        when(params.getEnvironment()).thenReturn(env);

        return params;
    }
}
