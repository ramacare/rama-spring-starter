package org.rama.demo.controller.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.controller.system.ClientConfigController;
import org.rama.controller.system.ClientUserConfigController;
import org.rama.entity.system.ClientConfig;
import org.rama.entity.system.ClientUserConfig;
import org.rama.repository.system.ClientConfigRepository;
import org.rama.repository.system.ClientUserConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cover for starter#52: single-record lookups by primary key, so callers don't need
 * {@code clientConfig}/{@code clientUserConfig}'s unbounded {@code findAll()} just to fetch
 * one row. Implemented as explicit resolvers (not left to auto-registration) for the same
 * reason as starter#51 -- both entities' {@code configuration} field defaults to a non-null
 * {@code new HashMap<>()}, which would corrupt an auto-registered probe-based lookup too.
 *
 * <p>The starter's own controllers live in {@code org.rama.controller}, which the demo
 * application does not component-scan (see {@link UserScopedConfigIT}), so they are imported
 * explicitly here.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import({ClientConfigController.class, ClientUserConfigController.class})
class ClientConfigByIdIT {

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired ClientConfigRepository clientConfigRepository;
    @Autowired ClientUserConfigRepository clientUserConfigRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void clientConfigById_whenExists_shouldReturnTheRow() {
        ClientConfig seeded = transactionTemplate.execute(s -> {
            ClientConfig c = new ClientConfig();
            c.setComputerName("BY-ID-PC");
            c.setFingerprint("by-id-fp");
            c.setLastSeenDatetime(OffsetDateTime.now());
            return clientConfigRepository.saveAndFlush(c);
        });

        graphQlTester.document("""
            query($id: ID!) {
              clientConfigById(id: $id) { id computerName }
            }
            """)
            .variable("id", String.valueOf(seeded.getId()))
            .execute()
            .path("clientConfigById.computerName").entity(String.class).isEqualTo("BY-ID-PC");
    }

    @Test
    void clientConfigById_whenMissing_shouldReturnNull() {
        graphQlTester.document("""
            query {
              clientConfigById(id: "999999999") { id }
            }
            """)
            .execute()
            .path("clientConfigById").valueIsNull();
    }

    @Test
    void clientUserConfigById_whenExists_shouldReturnTheRow() {
        ClientUserConfig seeded = transactionTemplate.execute(s -> {
            ClientUserConfig c = new ClientUserConfig();
            c.setClientUsername("RAMA\\by-id-user");
            c.setLastSeenDatetime(OffsetDateTime.now());
            return clientUserConfigRepository.saveAndFlush(c);
        });

        graphQlTester.document("""
            query($id: ID!) {
              clientUserConfigById(id: $id) { id clientUsername }
            }
            """)
            .variable("id", String.valueOf(seeded.getId()))
            .execute()
            .path("clientUserConfigById.clientUsername").entity(String.class).isEqualTo("RAMA\\by-id-user");

        assertThat(clientUserConfigRepository.findById(seeded.getId())).isPresent();
    }
}
