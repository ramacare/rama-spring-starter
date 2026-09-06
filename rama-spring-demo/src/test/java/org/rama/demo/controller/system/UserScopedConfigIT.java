package org.rama.demo.controller.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.controller.system.ClientConfigController;
import org.rama.controller.system.ClientUserConfigController;
import org.rama.controller.system.UserConfigController;
import org.rama.repository.system.ClientUserConfigRepository;
import org.rama.repository.system.UserConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end cover for the two user-scoped config entities. The starter's own controllers live in
 * {@code org.rama.controller}, which the demo application does not component-scan, so they are
 * imported explicitly here -- that is what makes the schema-to-controller mapping testable.
 *
 * <p>The demo runs with {@code spring.jpa.open-in-view=false}, so these tests also exercise the
 * transaction boundary fixed in starter#36.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import({ClientUserConfigController.class, UserConfigController.class, ClientConfigController.class})
class UserScopedConfigIT {

    /** Seeded by db/changelog/seed-apiKey.yaml; authenticates as principal "demo-user". */
    private static final String USER_API_KEY = "demo-user-key";

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired ClientUserConfigRepository clientUserConfigRepository;
    @Autowired UserConfigRepository userConfigRepository;

    private HttpGraphQlTester authenticated() {
        return graphQlTester.mutate().headers(h -> h.set("X-API-KEY", USER_API_KEY)).build();
    }

    @Test
    void clientUserConfigByClientUsername_whenUnknown_shouldRegisterTheRow() {
        graphQlTester.document("""
            query {
              clientUserConfigByClientUsername(clientUsername: "RAMA\\\\somchai") {
                id clientUsername configuration lastSeenDatetime
              }
            }
            """)
            .execute()
            .path("clientUserConfigByClientUsername.clientUsername").entity(String.class).isEqualTo("RAMA\\somchai")
            .path("clientUserConfigByClientUsername.lastSeenDatetime").entity(String.class)
            .satisfies(seen -> assertThat(seen).isNotBlank());

        assertThat(clientUserConfigRepository.existsByClientUsername("RAMA\\somchai")).isTrue();
    }

    @Test
    void clientUserConfigByClientUsername_whenCalledTwice_shouldReturnTheSameRow() {
        String query = """
            query {
              clientUserConfigByClientUsername(clientUsername: "RAMA\\\\repeat") { id }
            }
            """;

        String first = graphQlTester.document(query).execute()
                .path("clientUserConfigByClientUsername.id").entity(String.class).get();
        String second = graphQlTester.document(query).execute()
                .path("clientUserConfigByClientUsername.id").entity(String.class).get();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void createThenUpdateClientUserConfig_shouldPersistConfiguration() {
        String id = graphQlTester.document("""
            mutation {
              createClientUserConfig(input: {clientUsername: "RAMA\\\\editor", configuration: {theme: "dark"}}) { id }
            }
            """)
            .execute()
            .path("createClientUserConfig.id").entity(String.class).get();

        graphQlTester.document("""
            mutation($id: ID!) {
              updateClientUserConfig(input: {id: $id, clientUsername: "RAMA\\\\editor", configuration: {theme: "light"}}) {
                configuration
              }
            }
            """)
            .variable("id", id)
            .execute()
            .path("updateClientUserConfig.configuration.theme").entity(String.class).isEqualTo("light");
    }

    @Test
    void userConfig_shouldResolveTheAuthenticatedPrincipal() {
        authenticated().document("""
            query { userConfig { id username lastSeenDatetime } }
            """)
            .execute()
            .path("userConfig.username").entity(String.class).isEqualTo("demo-user");

        assertThat(userConfigRepository.existsByUsername("demo-user")).isTrue();
    }

    /** An unauthenticated caller is anonymous, not a user -- no row may be registered for it. */
    @Test
    void userConfig_whenAnonymous_shouldReturnNullAndRegisterNothing() {
        graphQlTester.document("""
            query { userConfig { id username } }
            """)
            .execute()
            .path("userConfig").valueIsNull();

        assertThat(userConfigRepository.existsByUsername("anonymousUser")).isFalse();
    }

    @Test
    void userConfigByUsername_whenUnknown_shouldRegisterTheRow() {
        graphQlTester.document("""
            query { userConfigByUsername(username: "malee") { id username configuration } }
            """)
            .execute()
            .path("userConfigByUsername.username").entity(String.class).isEqualTo("malee");

        assertThat(userConfigRepository.existsByUsername("malee")).isTrue();
    }

    @Test
    void userConfigPageable_shouldListRegisteredRows() {
        graphQlTester.document("""
            query { userConfigByUsername(username: "listed") { id } }
            """).execute();

        graphQlTester.document("""
            query { userConfigPageable(pageable: {page: 1, perPage: 50}) { data { username } } }
            """)
            .execute()
            .path("userConfigPageable.data[*].username").entityList(String.class)
            .satisfies(names -> assertThat(names).contains("listed"));
    }

    @Test
    void userConfigByExamplePageable_shouldFilterByUsername() {
        graphQlTester.document("""
            query { userConfigByUsername(username: "filtered") { id } }
            """).execute();

        graphQlTester.document("""
            query {
              userConfigByExamplePageable(example: {username: "filtered"}, pageable: {page: 1, perPage: 10}) {
                data { username }
              }
            }
            """)
            .execute()
            .path("userConfigByExamplePageable.data[*].username").entityList(String.class)
            .satisfies(names -> assertThat(names).containsExactly("filtered"));
    }

    /**
     * Regression guard for starter#36: with open-in-view disabled, create mutations failed with
     * "Given entity is not associated with the persistence context" because the entityIdKey
     * overload self-invoked past its @Transactional proxy.
     */
    @Test
    void createClientConfig_shouldSucceedWithOpenInViewDisabled() {
        graphQlTester.document("""
            mutation {
              createClientConfig(input: {computerName: "PROBE-PC", fingerprint: "probe-fp"}) { id computerName }
            }
            """)
            .execute()
            .path("createClientConfig.computerName").entity(String.class).isEqualTo("PROBE-PC");
    }
}
