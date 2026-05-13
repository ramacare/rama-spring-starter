package org.rama.demo.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.rama.job.system.SystemBufferDrainJob;
import org.rama.repository.system.SystemBufferRepository;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@Testcontainers
@SpringBootTest
@ActiveProfiles("h2")
@EnabledIf("dockerAvailable")
class RevisionOutboxClickHouseIntegrationIT {

    @SuppressWarnings("unused") // referenced by @EnabledIf above
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Container
    static ClickHouseContainer clickhouse = new ClickHouseContainer(
            DockerImageName.parse("clickhouse/clickhouse-server:24.8"));

    @DynamicPropertySource
    static void clickHouseProps(DynamicPropertyRegistry registry) {
        registry.add("rama.revision.clickhouse.enabled", () -> "true");
        registry.add("rama.revision.clickhouse.url", clickhouse::getJdbcUrl);
        registry.add("rama.revision.clickhouse.username", clickhouse::getUsername);
        registry.add("rama.revision.clickhouse.password", clickhouse::getPassword);
        registry.add("rama.revision.clickhouse.drain-interval", () -> "1s");
    }

    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired RevisionClickHouseRepository revisionClickHouseRepository;
    @Autowired SystemBufferRepository systemBufferRepository;
    @Autowired List<SystemBufferDispatcher> dispatchers;

    @Test
    void saveBook_revisionFlowsThroughOutboxToClickHouse() throws Exception {
        Book book = transactionTemplate.execute(s ->
                bookRepository.saveAndFlush(new Book("Outbox Test")));

        // Force a drain run directly instead of scheduling (demo excludes Quartz auto-config).
        SystemBufferDrainJob drainJob = new SystemBufferDrainJob(systemBufferRepository, dispatchers);
        drainJob.drainAll(1000);

        String revisionKey = "org.rama.demo.entity.book.Book^id^" + book.getId();

        await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            List<ClickHouseRevisionRecord> history = revisionClickHouseRepository.findHistory(revisionKey);
            assertThat(history).hasSizeGreaterThanOrEqualTo(1);
            assertThat(history.get(0).revisionKey()).isEqualTo(revisionKey);
        });
    }
}
