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
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Nested
    class NoAuthDirective {

        @Test
        void shouldReturnOriginalFetcherUnchanged() {
            GraphQLFieldDefinition field = GraphQLFieldDefinition.newFieldDefinition()
                    .name("publicField")
                    .type(graphql.Scalars.GraphQLString)
                    .build();

            InstrumentationFieldFetchParameters params = mockParams(field);
            DataFetcher<?> result = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(result).isSameAs(originalFetcher);
        }

        @Test
        void shouldNotCheckSecurityContext() throws Exception {
            GraphQLFieldDefinition field = GraphQLFieldDefinition.newFieldDefinition()
                    .name("publicField")
                    .type(graphql.Scalars.GraphQLString)
                    .build();

            InstrumentationFieldFetchParameters params = mockParams(field);
            DataFetcher<?> result = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("public data");

            Object value = result.get(dataFetchingEnvironment);

            assertThat(value).isEqualTo("public data");
            verify(originalFetcher).get(dataFetchingEnvironment);
        }
    }

    @Nested
    class AuthDirectiveWithoutRoles {

        @Test
        void whenNotAuthenticated_shouldThrowAuthenticationRequired() {
            GraphQLFieldDefinition field = buildFieldWithAuth(List.of(), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthenticationRequiredException.class)
                    .hasMessageContaining("Authentication is required");
            verifyNoInteractions(originalFetcher);
        }

        @Test
        void whenAnonymousUser_shouldThrowAuthenticationRequired() {
            SecurityContextHolder.getContext().setAuthentication(
                    new AnonymousAuthenticationToken("key", "anonymous",
                            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))
            );

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of(), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthenticationRequiredException.class);
            verifyNoInteractions(originalFetcher);
        }

        @Test
        void whenAuthenticated_shouldCallOriginalFetcher() throws Exception {
            setAuthenticated("user", "ROLE_USER");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("authenticated data");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of(), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
            Object result = wrapped.get(dataFetchingEnvironment);

            assertThat(result).isEqualTo("authenticated data");
            verify(originalFetcher).get(dataFetchingEnvironment);
        }
    }

    @Nested
    class AuthDirectiveWithSingleRole {

        @Test
        void whenHasExactRole_shouldCallOriginalFetcher() throws Exception {
            setAuthenticated("admin", "ROLE_ADMIN");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("admin data");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
            Object result = wrapped.get(dataFetchingEnvironment);

            assertThat(result).isEqualTo("admin data");
        }

        @Test
        void whenRoleWithoutPrefix_shouldMatchPrefixedAuthority() throws Exception {
            setAuthenticated("admin", "ROLE_ADMIN");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("admin data");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ADMIN"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);
            Object result = wrapped.get(dataFetchingEnvironment);

            assertThat(result).isEqualTo("admin data");
        }

        @Test
        void whenMissingRole_shouldThrowAuthorizationDenied() {
            setAuthenticated("user", "ROLE_USER");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthorizationDeniedException.class)
                    .hasMessageContaining("Access denied")
                    .hasMessageContaining("ROLE_ADMIN");
            verifyNoInteractions(originalFetcher);
        }

        @Test
        void whenNotAuthenticated_shouldThrowAuthenticationNotAuthorization() {
            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthenticationRequiredException.class);
        }
    }

    @Nested
    class AuthDirectiveWithMultipleRolesAny {

        @Test
        void whenHasFirstRole_shouldSucceed() throws Exception {
            setAuthenticated("admin", "ROLE_ADMIN");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }

        @Test
        void whenHasSecondRole_shouldSucceed() throws Exception {
            setAuthenticated("doctor", "ROLE_DOCTOR");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }

        @Test
        void whenHasNeitherRole_shouldThrowAuthorizationDenied() {
            setAuthenticated("nurse", "ROLE_NURSE");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ANY");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }

        @Test
        void defaultMatchMode_shouldBeAny() throws Exception {
            setAuthenticated("admin", "ROLE_ADMIN");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuthNoMatch(List.of("ROLE_ADMIN", "ROLE_DOCTOR"));
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }
    }

    @Nested
    class AuthDirectiveWithMultipleRolesAll {

        @Test
        void whenHasAllRoles_shouldSucceed() throws Exception {
            setAuthenticated("superuser", "ROLE_ADMIN", "ROLE_DOCTOR");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }

        @Test
        void whenHasOnlyFirstRole_shouldThrowAuthorizationDenied() {
            setAuthenticated("admin", "ROLE_ADMIN");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthorizationDeniedException.class)
                    .hasMessageContaining("ALL");
        }

        @Test
        void whenHasOnlySecondRole_shouldThrowAuthorizationDenied() {
            setAuthenticated("doctor", "ROLE_DOCTOR");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }

        @Test
        void whenHasNoRoles_shouldThrowAuthorizationDenied() {
            setAuthenticated("user", "ROLE_USER");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThatThrownBy(() -> wrapped.get(dataFetchingEnvironment))
                    .isInstanceOf(AuthorizationDeniedException.class);
        }

        @Test
        void whenHasAllRolesAndMore_shouldSucceed() throws Exception {
            setAuthenticated("superuser", "ROLE_ADMIN", "ROLE_DOCTOR", "ROLE_NURSE");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "ALL");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }

        @Test
        void matchAll_caseInsensitive_shouldWork() throws Exception {
            setAuthenticated("superuser", "ROLE_ADMIN", "ROLE_DOCTOR");
            when(originalFetcher.get(dataFetchingEnvironment)).thenReturn("result");

            GraphQLFieldDefinition field = buildFieldWithAuth(List.of("ROLE_ADMIN", "ROLE_DOCTOR"), "all");
            InstrumentationFieldFetchParameters params = mockParams(field);

            DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(originalFetcher, params, instrumentationState);

            assertThat(wrapped.get(dataFetchingEnvironment)).isEqualTo("result");
        }
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

    private GraphQLFieldDefinition buildFieldWithAuthNoMatch(List<String> roles) {
        GraphQLAppliedDirective.Builder directiveBuilder = GraphQLAppliedDirective.newDirective()
                .name("auth");

        if (!roles.isEmpty()) {
            directiveBuilder.argument(GraphQLAppliedDirectiveArgument.newArgument()
                    .name("roles")
                    .type(graphql.schema.GraphQLList.list(graphql.Scalars.GraphQLString))
                    .valueProgrammatic(roles)
                    .build());
        }

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
