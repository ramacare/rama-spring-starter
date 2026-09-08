package org.rama.demo.controller.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.controller.system.ClientConfigController;
import org.rama.entity.system.ClientConfig;
import org.rama.repository.system.ClientConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression cover for starter#51: {@code clientConfig} used to be left to Spring for
 * GraphQL's Querydsl/Query-by-Example auto-registration, which builds its implicit filter
 * from a fresh {@code new ClientConfig()} probe. Since {@code configuration} defaults to
 * {@code new HashMap<>()} rather than {@code null}, the auto-registered fetcher silently
 * added a {@code WHERE configuration = '{}'} predicate -- any row with a real configuration
 * (e.g. right after {@code updateClientConfig}) dropped out of the {@code clientConfig} list
 * even though {@code clientConfigByExamplePageable} and the repository itself still saw it.
 *
 * <p>The starter's own controllers live in {@code org.rama.controller}, which the demo
 * application does not component-scan (see {@link UserScopedConfigIT}), so it is imported
 * explicitly here.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(ClientConfigController.class)
class ClientConfigListAfterUpdateIT {

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired ClientConfigRepository clientConfigRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void clientConfig_afterUpdateSetsConfiguration_shouldStillListTheRow() {
        ClientConfig seeded = transactionTemplate.execute(s -> {
            ClientConfig c = new ClientConfig();
            c.setComputerName("SEED-COMPUTER");
            c.setFingerprint("seed-fp");
            c.setLastSeenDatetime(OffsetDateTime.now());
            return clientConfigRepository.saveAndFlush(c);
        });

        graphQlTester.document("""
            mutation($id: ID!) {
              updateClientConfig(input: {id: $id, computerName: "MI-C17088-B", configuration: { place: "SDMD" }}) {
                id computerName
              }
            }
            """)
            .variable("id", String.valueOf(seeded.getId()))
            .execute()
            .path("updateClientConfig.computerName").entity(String.class).isEqualTo("MI-C17088-B");

        List<String> namesFromList = graphQlTester.document("""
            query {
              clientConfig { id computerName configuration }
            }
            """)
            .execute()
            .path("clientConfig[*].computerName").entityList(String.class).get();

        assertThat(namesFromList).contains("MI-C17088-B");
    }
}
