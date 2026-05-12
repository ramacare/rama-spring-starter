package org.rama.demo.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseSink;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@Tag("integration")
@SpringBootTest
@ActiveProfiles({"h2", "clickhouse"})
@ExtendWith(SpringExtension.class)
@Testcontainers
class RevisionClickHouseFailoverIT {

    @Container
    static ClickHouseContainer clickhouse =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"));

    @DynamicPropertySource
    static void chProps(DynamicPropertyRegistry r) {
        r.add("rama.revision.clickhouse.enabled", () -> "true");
        r.add("rama.revision.clickhouse.url",
                () -> "jdbc:ch://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123) + "/default");
        r.add("rama.revision.clickhouse.username", clickhouse::getUsername);
        r.add("rama.revision.clickhouse.password", clickhouse::getPassword);
        r.add("rama.revision.clickhouse.batch-size", () -> "1");
        r.add("rama.revision.clickhouse.flush-interval", () -> "PT1S");
        r.add("rama.revision.clickhouse.max-queue-size", () -> "100");
    }

    @Autowired private RevisionClickHouseSink sink;
    @Autowired private RevisionService revisionService;

    @Test
    void sinkOffer_shouldNotThrow_whenClickHouseIsDown() {
        clickhouse.stop();   // simulate outage

        assertThatNoException().isThrownBy(() ->
                sink.offer(ClickHouseRevisionRecord.of(
                        99L, "Patient^id^99", null, "Patient", OffsetDateTime.now(),
                        "{}", null, null, null, null, null)));

        // Sink keeps row buffered for retry — confirm it's not silently dropped on success.
        assertThat(sink.queueSize()).isEqualTo(1);
    }

    @Test
    void getStateAt_shouldFallBackToSql_whenClickHouseIsDown() {
        clickhouse.stop();   // simulate outage

        // Should not throw — JPA fallback kicks in. (No rows in either tier for this key
        // in the H2 test DB, so Optional.empty is the expected non-error result.)
        assertThatNoException().isThrownBy(() ->
                revisionService.getStateAt("never-existed", OffsetDateTime.now()));
    }
}
