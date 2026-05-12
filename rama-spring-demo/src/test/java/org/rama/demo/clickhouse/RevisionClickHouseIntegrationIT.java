package org.rama.demo.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.clickhouse.RevisionClickHouseSink;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@SpringBootTest
@ActiveProfiles({"h2", "clickhouse"})
@ExtendWith(SpringExtension.class)
@Testcontainers
class RevisionClickHouseIntegrationIT {

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
        r.add("rama.revision.clickhouse.batch-size", () -> "2");
        r.add("rama.revision.clickhouse.flush-interval", () -> "PT1S");
    }

    @Autowired private RevisionClickHouseSink sink;
    @Autowired private RevisionClickHouseRepository repository;

    @Test
    void offer_thenGetStateAt_shouldRoundTrip() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime t1 = OffsetDateTime.parse("2026-02-01T00:00:00Z");

        sink.offer(ClickHouseRevisionRecord.of(
                1L, "Patient^id^1", "MRN1", "Patient", t0,
                "{\"name\":\"Old\"}", null, "alice", "alice", t0, t0));
        sink.offer(ClickHouseRevisionRecord.of(
                2L, "Patient^id^1", "MRN1", "Patient", t1,
                "{\"name\":\"New\"}", "{\"name\":\"New\"}", "alice", "alice", t1, t1));
        // batch-size = 2 so this flushes synchronously

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<ClickHouseRevisionRecord> mid =
                    repository.getStateAt("Patient^id^1", OffsetDateTime.parse("2026-01-15T00:00:00Z"));
            assertThat(mid).isPresent();
            assertThat(mid.get().id()).isEqualTo(1L);

            Optional<ClickHouseRevisionRecord> later =
                    repository.getStateAt("Patient^id^1", OffsetDateTime.parse("2026-03-01T00:00:00Z"));
            assertThat(later).isPresent();
            assertThat(later.get().id()).isEqualTo(2L);
        });
    }
}
