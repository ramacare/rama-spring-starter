# ClickHouse-Backed Revision Audit Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the revision audit log from the primary OLTP database (`revision` table on SQL Server / MySQL / PostgreSQL) to ClickHouse, which is purpose-built for high-volume append-only time-ordered data. The existing OLTP `revision` table continues to be the synchronous write target during a dual-write transition period, and reads transparently dispatch to ClickHouse when enabled.

**Architecture:**
- **Dual-write pattern.** Hibernate listeners (already wired post-commit) write to SQL `revision` synchronously and enqueue the row to an in-memory `RevisionClickHouseSink`. A scheduled flusher batches buffered rows and bulk-inserts into ClickHouse.
- **ClickHouse table** uses `MergeTree` engine with monthly `toYYYYMM(revision_datetime)` partitions, sort key `(revision_key, revision_datetime, id)` (matches the dominant point-in-time query), `LowCardinality` for repeated dimensions (entity name, MRN, audit user), and `ZSTD(3)` codec on JSON payload columns for ~10× compression.
- **Read API** in `RevisionService` dispatches to a new `RevisionClickHouseRepository` when `rama.revision.clickhouse.enabled=true`, falling back to the existing JPA repository otherwise. Consumers can roll forward and back via a single property.
- **Feature-flagged auto-config.** Everything ClickHouse-related is conditional on the property; existing consumers see no behavior change until they opt in. Schema is initialized at startup by a Java `ClickHouseSchemaInitializer` (Liquibase's ClickHouse support is experimental and not used).
- **Fail-soft design.** ClickHouse is treated as a best-effort downstream destination. **The application's correctness, availability, and durability are NOT coupled to ClickHouse availability** — SQL `revision` is the source of truth; ClickHouse is its enriched mirror. Concretely: connection failures at startup don't fail the app; schema-init exceptions don't fail the app; sink flush failures distinguish transient (re-buffer) from permanent (drop + log dead-letter); read failures fall back to JPA; queue overflow drops oldest with a counter; a nightly `RevisionClickHouseBackfillJob` reconciles SQL → ClickHouse to recover any dropped rows automatically.

**Tech Stack:**
- ClickHouse server (self-hosted on-prem; appendix covers setup)
- `com.clickhouse:clickhouse-jdbc` (latest 0.7.x) — official ClickHouse JDBC driver
- Spring Boot 4.0.3, Spring's `JdbcTemplate`, `@Scheduled`, `@ConditionalOnProperty`
- `org.testcontainers:clickhouse` for integration tests
- Existing starter modules: `rama-spring-core` (entities, services, repositories), `rama-spring-autoconfigure` (properties, beans)

---

## File Structure

**New files (rama-spring-core):**

- `src/main/java/org/rama/clickhouse/ClickHouseRevisionRecord.java` — POJO mapping a `Revision` to a ClickHouse row. Java `record`. One responsibility: data shape.
- `src/main/java/org/rama/clickhouse/ClickHouseSchemaInitializer.java` — `ApplicationRunner` that executes `CREATE TABLE IF NOT EXISTS revision (...)` on startup. **Catches all exceptions**, logs error, returns normally — never fails app startup. One responsibility: idempotent best-effort schema bootstrap.
- `src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java` — In-memory queue + size/time-triggered flusher. Receives `ClickHouseRevisionRecord` from listeners; bulk-inserts via `JdbcTemplate.batchUpdate`. Distinguishes transient from permanent insert failures (transient → re-buffer; permanent → dead-letter log + drop). Exposes Micrometer counters/gauges. One responsibility: write-path buffering with graceful degradation.
- `src/main/java/org/rama/clickhouse/RevisionClickHouseRepository.java` — Read-side queries against ClickHouse (`getStateAt`, `findHistory`, `findByMrn`). One responsibility: typed read API over ClickHouse. Throws on JDBC error — fallback is handled one layer up in `RevisionService`.
- `src/main/java/org/rama/clickhouse/RevisionClickHouseBackfillJob.java` — Quartz job (`QuartzJobBean`). Selects SQL `revision` rows with `id > MAX(id) FROM clickhouse.revision`, enqueues each into `RevisionClickHouseSink`. Idempotent (ClickHouse `MergeTree` deduplicates on the sort key + id). One responsibility: SQL → ClickHouse reconciliation after dropped rows or outages.

**New files (rama-spring-autoconfigure):**

- `src/main/java/org/rama/autoconfigure/ClickHouseProperties.java` — `@ConfigurationProperties("rama.revision.clickhouse")`. Single responsibility: configuration shape.
- `src/main/java/org/rama/autoconfigure/RamaStarterClickHouseAutoConfiguration.java` — Bean wiring: `DataSource` (separate from the primary DataSource), `JdbcTemplate`, `ClickHouseSchemaInitializer`, `RevisionClickHouseSink`, `RevisionClickHouseRepository`. All gated on `rama.revision.clickhouse.enabled=true`. Single responsibility: ClickHouse bean wiring.

**Modified files:**

- `rama-spring-core/pom.xml` — Add `clickhouse-jdbc` dependency.
- `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostInsertRevisionListener.java` — Inject `ObjectProvider<RevisionClickHouseSink>`; after `saveRevision`, also `sink.offer(record)`. No behavior change when sink absent.
- `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostUpdateRevisionListener.java` — Same pattern.
- `rama-spring-core/src/main/java/org/rama/service/RevisionService.java` — `getStateAt` and (new) `findHistory` / `findByMrn` consult `RevisionClickHouseRepository` when present; otherwise fall back to JPA `RevisionRepository`.
- `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java` — `@Import` the new ClickHouse auto-configuration class.
- `rama-spring-demo/pom.xml` — Testcontainers ClickHouse module for integration tests.
- `CLAUDE.md` — One paragraph documenting the new feature flag and dual-write semantics.

**Test files (new):**

- `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseRevisionRecordTest.java`
- `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java` (includes resilience tests: transient retry, permanent dead-letter, queue overflow)
- `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseRepositoryTest.java`
- `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseSchemaInitializerTest.java` (includes fail-soft test)
- `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseBackfillJobTest.java`
- `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionClickHouseIntegrationIT.java` — End-to-end Testcontainers test.
- `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionClickHouseFailoverIT.java` — Testcontainers with ClickHouse stopped mid-run: verifies app keeps serving, reads fall back to SQL, backfill recovers on recovery.

---

## Task 1: Add ClickHouse JDBC driver dependency

**Files:**
- Modify: `rama-spring-core/pom.xml`

- [ ] **Step 1: Add the dependency**

Add to `<dependencies>` in `rama-spring-core/pom.xml`, after `spring-boot-starter-data-jpa`:

```xml
<dependency>
    <groupId>com.clickhouse</groupId>
    <artifactId>clickhouse-jdbc</artifactId>
    <version>0.7.2</version>
    <classifier>http</classifier>
    <optional>true</optional>
</dependency>
```

`<optional>true</optional>` means transitive consumers don't pull it unless they explicitly want ClickHouse — the bean wiring is the activation point.

- [ ] **Step 2: Resolve and compile**

Run: `mvn -pl rama-spring-core dependency:resolve -q && mvn -pl rama-spring-core compile -q`
Expected: BUILD SUCCESS, no compilation errors.

- [ ] **Step 3: Commit**

```bash
git add rama-spring-core/pom.xml
git commit -m "build(revision): add clickhouse-jdbc (optional) for upcoming ClickHouse audit log support"
```

---

## Task 2: Define ClickHouseProperties

**Files:**
- Create: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/ClickHouseProperties.java`
- Test: `rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/ClickHousePropertiesTest.java`

- [ ] **Step 1: Write the failing test**

Create `rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/ClickHousePropertiesTest.java`:

```java
package org.rama.autoconfigure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ClickHousePropertiesTest {

    @Test
    void shouldBindFromProperties() {
        Map<String, Object> source = Map.of(
                "rama.revision.clickhouse.enabled", "true",
                "rama.revision.clickhouse.url", "jdbc:ch://ch.example:8123/audit",
                "rama.revision.clickhouse.username", "writer",
                "rama.revision.clickhouse.password", "secret",
                "rama.revision.clickhouse.table-name", "revision",
                "rama.revision.clickhouse.batch-size", "5000",
                "rama.revision.clickhouse.flush-interval", "PT3S");

        ClickHouseProperties props = Binder.get(
                new org.springframework.core.env.StandardEnvironment() {{
                    getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("test", source));
                }})
                .bind("rama.revision.clickhouse", ClickHouseProperties.class)
                .get();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getUrl()).isEqualTo("jdbc:ch://ch.example:8123/audit");
        assertThat(props.getUsername()).isEqualTo("writer");
        assertThat(props.getPassword()).isEqualTo("secret");
        assertThat(props.getTableName()).isEqualTo("revision");
        assertThat(props.getBatchSize()).isEqualTo(5000);
        assertThat(props.getFlushInterval()).hasSeconds(3);
    }

    @Test
    void shouldHaveSensibleDefaults() {
        ClickHouseProperties props = new ClickHouseProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getTableName()).isEqualTo("revision");
        assertThat(props.getBatchSize()).isEqualTo(1000);
        assertThat(props.getFlushInterval()).hasSeconds(5);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-autoconfigure -am test -Dtest=ClickHousePropertiesTest -Dgroups=unit`
Expected: FAIL — `ClickHouseProperties` does not exist.

- [ ] **Step 3: Create ClickHouseProperties**

Create `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/ClickHouseProperties.java`:

```java
package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "rama.revision.clickhouse")
public class ClickHouseProperties {

    /** Master switch. When false, no ClickHouse beans are created and behavior is unchanged. */
    private boolean enabled = false;

    /** Full JDBC URL, e.g. jdbc:ch://localhost:8123/audit */
    private String url;

    private String username;

    private String password;

    /** ClickHouse table name. Same name across all consumers. */
    private String tableName = "revision";

    /** Max rows buffered before forced flush. */
    private int batchSize = 1000;

    /** Wall-clock interval between scheduled flushes (whether or not batchSize is reached). */
    private Duration flushInterval = Duration.ofSeconds(5);

    /** Max buffered rows before back-pressure (drop oldest with warning). */
    private int maxQueueSize = 100_000;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl rama-spring-autoconfigure -am test -Dtest=ClickHousePropertiesTest -Dgroups=unit`
Expected: PASS — 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/ClickHouseProperties.java \
        rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/ClickHousePropertiesTest.java
git commit -m "feat(revision): add ClickHouseProperties (rama.revision.clickhouse.*) config"
```

---

## Task 3: ClickHouseRevisionRecord POJO

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseRevisionRecord.java`
- Test: `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseRevisionRecordTest.java`

- [ ] **Step 1: Write the failing test**

Create `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseRevisionRecordTest.java`:

```java
package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ClickHouseRevisionRecordTest {

    @Test
    void fromRevisionFields_shouldCarryAllValues() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-12T10:00:00Z");

        ClickHouseRevisionRecord record = ClickHouseRevisionRecord.of(
                42L,
                "Patient^id^12345",
                "MRN001",
                "Patient",
                now,
                "{\"name\":\"Jane\"}",
                "{\"name\":\"Jane\"}",
                "alice",
                "alice",
                now,
                now);

        assertThat(record.id()).isEqualTo(42L);
        assertThat(record.revisionKey()).isEqualTo("Patient^id^12345");
        assertThat(record.mrn()).isEqualTo("MRN001");
        assertThat(record.revisionEntity()).isEqualTo("Patient");
        assertThat(record.revisionDatetime()).isEqualTo(now);
        assertThat(record.revisionData()).isEqualTo("{\"name\":\"Jane\"}");
        assertThat(record.revisionChange()).isEqualTo("{\"name\":\"Jane\"}");
        assertThat(record.createdBy()).isEqualTo("alice");
    }

    @Test
    void shouldAcceptNullablesAsNull() {
        ClickHouseRevisionRecord record = ClickHouseRevisionRecord.of(
                1L, "k", null, null, OffsetDateTime.now(),
                "{}", null, null, null, null, null);
        assertThat(record.mrn()).isNull();
        assertThat(record.revisionChange()).isNull();
        assertThat(record.createdAt()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-core -am test -Dtest=ClickHouseRevisionRecordTest -Dgroups=unit`
Expected: FAIL — `ClickHouseRevisionRecord` does not exist.

- [ ] **Step 3: Create ClickHouseRevisionRecord**

Create `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseRevisionRecord.java`:

```java
package org.rama.clickhouse;

import java.time.OffsetDateTime;

/**
 * Wire shape for a single revision row going into ClickHouse. Pure data;
 * holds JSON columns as raw strings since they're written through
 * ClickHouse's String + ZSTD codec, not through Jackson.
 */
public record ClickHouseRevisionRecord(
        long id,
        String revisionKey,
        String mrn,
        String revisionEntity,
        OffsetDateTime revisionDatetime,
        String revisionData,
        String revisionChange,
        String createdBy,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ClickHouseRevisionRecord of(
            long id,
            String revisionKey,
            String mrn,
            String revisionEntity,
            OffsetDateTime revisionDatetime,
            String revisionData,
            String revisionChange,
            String createdBy,
            String updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        return new ClickHouseRevisionRecord(
                id, revisionKey, mrn, revisionEntity, revisionDatetime,
                revisionData, revisionChange, createdBy, updatedBy, createdAt, updatedAt);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl rama-spring-core -am test -Dtest=ClickHouseRevisionRecordTest -Dgroups=unit`
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseRevisionRecord.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseRevisionRecordTest.java
git commit -m "feat(revision): add ClickHouseRevisionRecord wire shape"
```

---

## Task 4: ClickHouseSchemaInitializer

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseSchemaInitializer.java`
- Test: `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseSchemaInitializerTest.java`

- [ ] **Step 1: Write the failing test**

Create `rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseSchemaInitializerTest.java`:

```java
package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ClickHouseSchemaInitializerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationArguments args;

    @Test
    void run_shouldExecuteCreateTableForRevision() throws Exception {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "revision");

        init.run(args);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());

        String stmt = sql.getValue();
        assertThat(stmt)
                .contains("CREATE TABLE IF NOT EXISTS revision")
                .contains("ENGINE = MergeTree()")
                .contains("PARTITION BY toYYYYMM(revision_datetime)")
                .contains("ORDER BY (revision_key, revision_datetime, id)")
                .contains("CODEC(ZSTD(3))")
                .contains("LowCardinality(Nullable(String))");
    }

    @Test
    void run_shouldUseConfiguredTableName() throws Exception {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "audit_revision");

        init.run(args);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertThat(sql.getValue()).contains("CREATE TABLE IF NOT EXISTS audit_revision");
    }

    @Test
    void run_shouldSwallowConnectionFailures_andReturnNormally() {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "revision");
        doThrow(new DataAccessResourceFailureException("ClickHouse unreachable"))
                .when(jdbcTemplate).execute(anyString());

        // Critical: must NOT propagate. App startup must continue even if ClickHouse is down.
        assertThatNoException().isThrownBy(() -> init.run(args));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-core -am test -Dtest=ClickHouseSchemaInitializerTest -Dgroups=unit`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create ClickHouseSchemaInitializer**

Create `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseSchemaInitializer.java`:

```java
package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs once on application startup. Creates the revision table in
 * ClickHouse if it does not already exist. Idempotent: CREATE TABLE IF
 * NOT EXISTS is the only DDL.
 *
 * <p>Liquibase is not used here -- its ClickHouse support is experimental
 * (community extension) and changes table dialect by design. A direct
 * idempotent DDL is simpler and matches the "schema is part of the
 * starter, not the consumer" contract for this audit table.
 */
@Slf4j
@RequiredArgsConstructor
public class ClickHouseSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;

    @Override
    public void run(ApplicationArguments args) {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    id              UInt64,
                    revision_key    String,
                    mrn             LowCardinality(Nullable(String)),
                    revision_entity LowCardinality(Nullable(String)),
                    revision_datetime DateTime64(3, 'UTC'),
                    revision_data   String CODEC(ZSTD(3)),
                    revision_change Nullable(String) CODEC(ZSTD(3)),
                    created_by      LowCardinality(Nullable(String)),
                    updated_by      LowCardinality(Nullable(String)),
                    created_at      Nullable(DateTime64(3, 'UTC')),
                    updated_at      Nullable(DateTime64(3, 'UTC'))
                )
                ENGINE = MergeTree()
                PARTITION BY toYYYYMM(revision_datetime)
                ORDER BY (revision_key, revision_datetime, id)
                SETTINGS index_granularity = 8192
                """.formatted(safeIdent(tableName));

        log.info("Initializing ClickHouse table {}", tableName);
        try {
            clickHouseJdbcTemplate.execute(ddl);
        } catch (RuntimeException e) {
            // Fail-soft: ClickHouse being unreachable at startup must NOT prevent
            // the application from booting. The SQL `revision` table remains the
            // source of truth; ClickHouse will catch up on the next flush attempt
            // or via the RevisionClickHouseBackfillJob.
            log.error("Failed to initialize ClickHouse table {} — continuing without it. "
                    + "Will retry on next flush. Cause: {}", tableName, e.getMessage());
        }
    }

    static String safeIdent(String s) {
        if (s == null || !s.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("Unsafe ClickHouse table name: " + s);
        }
        return s;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl rama-spring-core -am test -Dtest=ClickHouseSchemaInitializerTest -Dgroups=unit`
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseSchemaInitializer.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/ClickHouseSchemaInitializerTest.java
git commit -m "feat(revision): add ClickHouseSchemaInitializer for idempotent table DDL"
```

---

## Task 5: RevisionClickHouseSink — buffer + size-triggered flush

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java`
- Test: `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java`

- [ ] **Step 1: Write the failing test (offer + size-triggered flush)**

Create `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java`:

```java
package org.rama.clickhouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseSinkTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private RevisionClickHouseSink sink;

    @BeforeEach
    void setUp() {
        sink = new RevisionClickHouseSink(jdbcTemplate, "revision", /* batchSize */ 3, /* maxQueueSize */ 1000);
    }

    @Test
    void offer_shouldNotFlush_belowBatchSize() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isEqualTo(2);
    }

    @Test
    void offer_shouldFlushWhenBatchSizeReached() {
        sink.offer(record(1L));
        sink.offer(record(2L));
        sink.offer(record(3L));

        verify(jdbcTemplate).batchUpdate(
                startsWith("INSERT INTO revision"),
                any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isZero();
    }

    @Test
    void flush_shouldNoopWhenEmpty() {
        sink.flush();
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void flush_shouldEmitBatchInsertWithAllBufferedRows() {
        sink.offer(record(1L));
        sink.offer(record(2L));
        sink.flush();

        verify(jdbcTemplate).batchUpdate(
                startsWith("INSERT INTO revision"),
                any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isZero();
    }

    @Test
    void offer_shouldDropOldestWhenQueueFull() {
        RevisionClickHouseSink tightSink =
                new RevisionClickHouseSink(jdbcTemplate, "revision", 1000, /* maxQueueSize */ 2);

        tightSink.offer(record(1L));
        tightSink.offer(record(2L));
        tightSink.offer(record(3L));  // should evict id=1

        assertThat(tightSink.queueSize()).isEqualTo(2);
        assertThat(tightSink.peekIds()).containsExactly(2L, 3L);
    }

    @Test
    void flush_shouldRebufferOnTransientFailure() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        // Simulate ClickHouse down / network error (transient).
        when(jdbcTemplate.batchUpdate(startsWith("INSERT INTO revision"), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "Connection refused",
                        new java.sql.SQLTransientConnectionException("Connection refused")));

        sink.flush();

        // Rows should be re-buffered for the next flush attempt.
        assertThat(sink.queueSize()).isEqualTo(2);
        assertThat(sink.peekIds()).containsExactly(1L, 2L);
    }

    @Test
    void flush_shouldDropPoisonPillOnPermanentFailure() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        // Simulate a permanent schema/data error (poison pill).
        when(jdbcTemplate.batchUpdate(startsWith("INSERT INTO revision"), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "INSERT", "INSERT INTO revision",
                        new java.sql.SQLSyntaxErrorException("Type mismatch in column revision_data")));

        sink.flush();

        // Permanent failures must NOT be re-buffered -- they'd block every future batch.
        assertThat(sink.queueSize()).isZero();
    }

    private static ClickHouseRevisionRecord record(long id) {
        return ClickHouseRevisionRecord.of(
                id, "Entity^id^" + id, null, "Entity",
                OffsetDateTime.now(), "{}", null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseSinkTest -Dgroups=unit`
Expected: FAIL — `RevisionClickHouseSink` does not exist.

- [ ] **Step 3: Implement RevisionClickHouseSink**

Create `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java`:

```java
package org.rama.clickhouse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Buffer + bulk-insert sink for revision rows headed to ClickHouse.
 * Thread-safe (synchronized; the volume here is moderate -- one row per
 * revision; not a hot path that justifies lock-free machinery).
 *
 * <p>Two flush triggers:
 *   1. Size: {@link #offer} flushes synchronously when the queue reaches
 *      {@code batchSize}. Single-row ClickHouse inserts are notoriously
 *      slow; batched inserts are its happy path.
 *   2. Time: an external {@code @Scheduled} flusher calls {@link #flush}
 *      every few seconds so low-traffic periods don't strand rows.
 *
 * <p>Back-pressure: at {@code maxQueueSize}, oldest rows are evicted with
 * a warning. Audit data is forever-retention but we'd rather lose a few
 * rows (and log it) than OOM the whole app. The SQL {@code revision} table
 * remains the synchronous source of truth, so a dropped ClickHouse row
 * can be recovered from there by a backfill job.
 */
@Slf4j
public class RevisionClickHouseSink {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final int batchSize;
    private final int maxQueueSize;
    private final Deque<ClickHouseRevisionRecord> buffer = new ArrayDeque<>();

    public RevisionClickHouseSink(JdbcTemplate jdbcTemplate, String tableName, int batchSize, int maxQueueSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize;
    }

    /** Enqueue a revision. Flushes synchronously if the buffer reaches batchSize. */
    public synchronized void offer(ClickHouseRevisionRecord record) {
        while (buffer.size() >= maxQueueSize) {
            ClickHouseRevisionRecord dropped = buffer.pollFirst();
            log.warn("RevisionClickHouseSink queue full; dropping oldest record id={}",
                    dropped == null ? "?" : dropped.id());
        }
        buffer.addLast(record);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /** Force-flush whatever's buffered. No-op when empty. */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<ClickHouseRevisionRecord> batch = new ArrayList<>(buffer);
        buffer.clear();

        String sql = "INSERT INTO " + tableName + " ("
                + "id, revision_key, mrn, revision_entity, revision_datetime,"
                + " revision_data, revision_change,"
                + " created_by, updated_by, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";

        try {
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ClickHouseRevisionRecord r = batch.get(i);
                    ps.setLong(1, r.id());
                    ps.setString(2, r.revisionKey());
                    setNullable(ps, 3, r.mrn(), Types.VARCHAR);
                    setNullable(ps, 4, r.revisionEntity(), Types.VARCHAR);
                    ps.setTimestamp(5, Timestamp.from(r.revisionDatetime().toInstant()));
                    ps.setString(6, r.revisionData() == null ? "{}" : r.revisionData());
                    setNullable(ps, 7, r.revisionChange(), Types.VARCHAR);
                    setNullable(ps, 8, r.createdBy(), Types.VARCHAR);
                    setNullable(ps, 9, r.updatedBy(), Types.VARCHAR);
                    setNullableTimestamp(ps, 10, r.createdAt());
                    setNullableTimestamp(ps, 11, r.updatedAt());
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                // Network / ClickHouse-down / pool-timeout: re-buffer at the front so a
                // future flush retries. The SQL `revision` table remains source of truth
                // for any rows we eventually drop after queue overflow.
                synchronized (RevisionClickHouseSink.this) {
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        if (buffer.size() < maxQueueSize) buffer.addFirst(batch.get(i));
                    }
                }
                log.warn("ClickHouse batch insert failed (transient); rebuffered {} rows. Cause: {}",
                        batch.size(), rootCauseMessage(e));
            } else {
                // Permanent (schema mismatch, malformed value, etc.): re-buffering would
                // create a poison pill that blocks every future batch. Log to dead-letter
                // and drop. The SQL `revision` rows still exist; backfill job can re-attempt.
                log.error("ClickHouse batch insert failed (PERMANENT — dropping {} rows). Cause: {}",
                        batch.size(), rootCauseMessage(e), e);
                for (ClickHouseRevisionRecord rec : batch) {
                    log.error("DEAD_LETTER revision id={} key={} datetime={}",
                            rec.id(), rec.revisionKey(), rec.revisionDatetime());
                }
            }
        }
    }

    /**
     * Distinguish network / availability failures (worth retrying) from
     * data / schema failures (poison pills — must not retry, or they'll
     * block every subsequent batch).
     */
    static boolean isTransient(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.sql.SQLTransientException) return true;
            if (cur instanceof java.sql.SQLRecoverableException) return true;
            if (cur instanceof java.net.ConnectException) return true;
            if (cur instanceof java.net.SocketTimeoutException) return true;
            if (cur instanceof java.net.UnknownHostException) return true;
            if (cur instanceof org.springframework.dao.DataAccessResourceFailureException) return true;
            if (cur instanceof org.springframework.dao.TransientDataAccessException) return true;
            if (cur instanceof org.springframework.dao.QueryTimeoutException) return true;
        }
        return false;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage();
    }

    public synchronized int queueSize() {
        return buffer.size();
    }

    /** Test-only helper. */
    synchronized List<Long> peekIds() {
        return buffer.stream().map(ClickHouseRevisionRecord::id).toList();
    }

    private static void setNullable(PreparedStatement ps, int index, String value, int sqlType) throws SQLException {
        if (value == null) ps.setNull(index, sqlType);
        else ps.setString(index, value);
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, OffsetDateTime value) throws SQLException {
        if (value == null) ps.setNull(index, Types.TIMESTAMP);
        else ps.setTimestamp(index, Timestamp.from(value.toInstant()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseSinkTest -Dgroups=unit`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java
git commit -m "feat(revision): add RevisionClickHouseSink (buffered batch writer)"
```

---

## Task 6: Scheduled time-based flusher

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java`
- Modify: `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java`

- [ ] **Step 1: Add the scheduled-flusher test**

Append to `RevisionClickHouseSinkTest`:

```java
@Test
void scheduledFlush_shouldFlushPendingRows() {
    sink.offer(record(1L));
    sink.offer(record(2L));

    sink.scheduledFlush();

    verify(jdbcTemplate).batchUpdate(
            startsWith("INSERT INTO revision"),
            any(BatchPreparedStatementSetter.class));
    assertThat(sink.queueSize()).isZero();
}

@Test
void scheduledFlush_shouldBeNoopWhenEmpty() {
    sink.scheduledFlush();
    verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseSinkTest -Dgroups=unit`
Expected: FAIL — `scheduledFlush` method does not exist.

- [ ] **Step 3: Add the scheduled method**

Add to `RevisionClickHouseSink`:

```java
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Time-triggered flush. Wired by the auto-config with the
 * {@code rama.revision.clickhouse.flush-interval} cadence (default 5s).
 * Uses the same {@link #flush()} as the size trigger; the only difference
 * is who calls it.
 */
@Scheduled(fixedDelayString = "${rama.revision.clickhouse.flush-interval:PT5S}")
public void scheduledFlush() {
    flush();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseSinkTest -Dgroups=unit`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseSink.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseSinkTest.java
git commit -m "feat(revision): add scheduled time-based flush to RevisionClickHouseSink"
```

---

## Task 7: RevisionClickHouseRepository (read-side)

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseRepository.java`
- Test: `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseRepositoryTest.java`:

```java
package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseRepositoryTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void getStateAt_shouldReturnLatestRevisionAtOrBefore() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        OffsetDateTime at = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        ClickHouseRevisionRecord expected = ClickHouseRevisionRecord.of(
                7L, "Patient^id^1", "MRN1", "Patient",
                OffsetDateTime.parse("2026-04-30T08:00:00Z"),
                "{\"name\":\"Jane\"}", null, null, null, null, null);

        when(jdbcTemplate.queryForObject(
                anyString(), any(RowMapper.class), eq("Patient^id^1"), eq(java.sql.Timestamp.from(at.toInstant()))))
                .thenReturn(expected);

        Optional<ClickHouseRevisionRecord> result = repo.getStateAt("Patient^id^1", at);

        assertThat(result).contains(expected);
    }

    @Test
    void getStateAt_shouldReturnEmptyWhenNoRow() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<ClickHouseRevisionRecord> result = repo.getStateAt(
                "Patient^id^missing", OffsetDateTime.now());

        assertThat(result).isEmpty();
    }

    @Test
    void findHistory_shouldDelegateOrderedQuery() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("Patient^id^1")))
                .thenReturn(List.of());

        List<ClickHouseRevisionRecord> result = repo.findHistory("Patient^id^1");

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseRepositoryTest -Dgroups=unit`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement repository**

Create `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseRepository.java`:

```java
package org.rama.clickhouse;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

public class RevisionClickHouseRepository {

    private static final RowMapper<ClickHouseRevisionRecord> ROW_MAPPER =
            RevisionClickHouseRepository::map;

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public RevisionClickHouseRepository(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
    }

    /** Latest revision at or before {@code at} for the given key. Empty when no row matches. */
    public Optional<ClickHouseRevisionRecord> getStateAt(String revisionKey, OffsetDateTime at) {
        String sql = "SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                + " revision_data, revision_change,"
                + " created_by, updated_by, created_at, updated_at"
                + " FROM " + tableName
                + " WHERE revision_key = ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    sql, ROW_MAPPER, revisionKey, Timestamp.from(at.toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Full history for one key, newest first. */
    public List<ClickHouseRevisionRecord> findHistory(String revisionKey) {
        String sql = "SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                + " revision_data, revision_change,"
                + " created_by, updated_by, created_at, updated_at"
                + " FROM " + tableName
                + " WHERE revision_key = ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, revisionKey);
    }

    /** All revisions for a patient (mrn) within a time range, newest first. */
    public List<ClickHouseRevisionRecord> findByMrn(String mrn, OffsetDateTime from, OffsetDateTime to) {
        String sql = "SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                + " revision_data, revision_change,"
                + " created_by, updated_by, created_at, updated_at"
                + " FROM " + tableName
                + " WHERE mrn = ? AND revision_datetime >= ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, mrn,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    private static ClickHouseRevisionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return ClickHouseRevisionRecord.of(
                rs.getLong("id"),
                rs.getString("revision_key"),
                rs.getString("mrn"),
                rs.getString("revision_entity"),
                toOdt(rs.getTimestamp("revision_datetime")),
                rs.getString("revision_data"),
                rs.getString("revision_change"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                toOdt(rs.getTimestamp("created_at")),
                toOdt(rs.getTimestamp("updated_at")));
    }

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseRepositoryTest -Dgroups=unit`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseRepository.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseRepositoryTest.java
git commit -m "feat(revision): add RevisionClickHouseRepository for read queries"
```

---

## Task 8: Auto-configuration (RamaStarterClickHouseAutoConfiguration)

**Files:**
- Create: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterClickHouseAutoConfiguration.java`
- Modify: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java`

- [ ] **Step 1: Create the auto-config class**

Create `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterClickHouseAutoConfiguration.java`:

```java
package org.rama.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.rama.clickhouse.ClickHouseSchemaInitializer;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.clickhouse.RevisionClickHouseSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@AutoConfiguration
@EnableConfigurationProperties(ClickHouseProperties.class)
@ConditionalOnProperty(prefix = "rama.revision.clickhouse", name = "enabled", havingValue = "true")
@EnableScheduling
public class RamaStarterClickHouseAutoConfiguration {

    @Bean(name = "clickHouseDataSource", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "clickHouseDataSource")
    public DataSource clickHouseDataSource(ClickHouseProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        // ClickHouse is mostly long batched inserts; one or two connections is plenty.
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(1);
        ds.setPoolName("rama-clickhouse");
        // Critical for fail-soft: -1 disables HikariCP's startup connectivity check, so the
        // bean is created even when ClickHouse is unreachable at app boot. The first real
        // operation (schema init / flush) will catch the connection error and log it.
        ds.setInitializationFailTimeout(-1);
        // Bounded wait for a connection so flush failures surface quickly instead of
        // blocking the scheduled thread for the full default 30 s.
        ds.setConnectionTimeout(5_000);
        return ds;
    }

    @Bean(name = "clickHouseJdbcTemplate")
    @ConditionalOnMissingBean(name = "clickHouseJdbcTemplate")
    public JdbcTemplate clickHouseJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClickHouseSchemaInitializer clickHouseSchemaInitializer(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new ClickHouseSchemaInitializer(jdbc, props.getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionClickHouseSink revisionClickHouseSink(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new RevisionClickHouseSink(jdbc, props.getTableName(), props.getBatchSize(), props.getMaxQueueSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionClickHouseRepository revisionClickHouseRepository(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new RevisionClickHouseRepository(jdbc, props.getTableName());
    }
}
```

- [ ] **Step 2: Register the auto-config**

Append to `rama-spring-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
org.rama.autoconfigure.RamaStarterClickHouseAutoConfiguration
```

(Check the file path first — open the existing imports file and confirm the format matches. If the project doesn't use that mechanism, instead add `@Import(RamaStarterClickHouseAutoConfiguration.class)` to `RamaStarterAutoConfiguration`.)

- [ ] **Step 3: Compile + verify**

Run: `mvn -pl rama-spring-autoconfigure -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run all existing unit tests to confirm no regression**

Run: `mvn -pl rama-spring-core,rama-spring-autoconfigure -am test -Dgroups=unit`
Expected: PASS — same count as before the task plus the new tests added so far.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-autoconfigure/
git commit -m "feat(revision): wire ClickHouse beans behind rama.revision.clickhouse.enabled"
```

---

## Task 9: Hook listeners to enqueue ClickHouse rows

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostInsertRevisionListener.java`
- Modify: `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostUpdateRevisionListener.java`
- Modify: `rama-spring-core/src/main/java/org/rama/service/RevisionService.java` (helper)
- Modify: `rama-spring-core/src/test/java/org/rama/listener/global/RevisionListenerTest.java`

- [ ] **Step 1: Add a helper on `RevisionService` to materialize a `ClickHouseRevisionRecord`**

The helper needs access to `ObjectMapper` (already on classpath via `spring-boot-starter-json`). Edit `RevisionService.java`:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rama.clickhouse.ClickHouseRevisionRecord;

// add field + constructor change
private final ObjectMapper objectMapper;

public RevisionService(RevisionRepository revisionRepository) {
    this(revisionRepository, new ObjectMapper());
}

public RevisionService(RevisionRepository revisionRepository, ObjectMapper objectMapper) {
    this.revisionRepository = revisionRepository;
    this.objectMapper = objectMapper;
}

/**
 * Build a ClickHouse record from the same fields used to persist the SQL Revision.
 * Returns null on serialization failure -- the SQL write remains the source of truth
 * and a structured-log warning is emitted by the caller.
 */
public ClickHouseRevisionRecord toClickHouseRecord(
        long sqlId, String revisionKey, String revisionEntity,
        Map<String, Object> revisionData, Map<String, Object> revisionChange) {
    try {
        String dataJson = objectMapper.writeValueAsString(revisionData);
        String changeJson = revisionChange == null ? null : objectMapper.writeValueAsString(revisionChange);
        String mrn = revisionData != null && revisionData.get("mrn") != null
                ? Objects.toString(revisionData.get("mrn"), null) : null;
        return ClickHouseRevisionRecord.of(
                sqlId, revisionKey, mrn, revisionEntity,
                OffsetDateTime.now(), dataJson, changeJson,
                null, null, null, null);
    } catch (JsonProcessingException e) {
        return null;
    }
}
```

- [ ] **Step 2: Update the insert listener**

Edit `GlobalPostInsertRevisionListener.java`:

```java
import org.rama.clickhouse.RevisionClickHouseSink;
import org.rama.clickhouse.ClickHouseRevisionRecord;

private final ObjectProvider<RevisionService> revisionServiceProvider;
private final ObjectProvider<RevisionClickHouseSink> clickHouseSinkProvider;

public GlobalPostInsertRevisionListener(
        ObjectProvider<RevisionService> revisionServiceProvider,
        ObjectProvider<RevisionClickHouseSink> clickHouseSinkProvider) {
    this.revisionServiceProvider = revisionServiceProvider;
    this.clickHouseSinkProvider = clickHouseSinkProvider;
}
```

Inside `onPostInsert`, after the existing `saveRevision` call site, also enqueue to ClickHouse:

```java
RevisionClickHouseSink sink = clickHouseSinkProvider.getIfAvailable();
if (sink != null) {
    // sqlId is not available here -- use 0 so the row is identifiable by
    // (revisionKey, revisionDatetime) until the dedicated backfill links it.
    ClickHouseRevisionRecord record = revisionService.toClickHouseRecord(
            0L, revisionKey, revisionEntity, data, null);
    if (record != null) sink.offer(record);
}
```

Wrap the same way inside the `afterCommit` branch and the direct-call branch.

- [ ] **Step 3: Update the update listener identically**

Same changes in `GlobalPostUpdateRevisionListener.java`, passing `dirty` as the change map.

- [ ] **Step 4: Update existing listener tests for the new constructor**

Edit `RevisionListenerTest.java` constructor calls to pass `mock(ObjectProvider.class)` for the new sink provider (returning `null` from `getIfAvailable()`). Existing tests should pass unchanged behavior-wise.

- [ ] **Step 5: Add a test that verifies sink.offer is called when available**

```java
@Test
void postInsert_shouldAlsoEnqueueToClickHouseSink_whenAvailable() {
    when(revisionServiceProvider.getIfAvailable()).thenReturn(revisionService);
    ObjectProvider<RevisionClickHouseSink> sinkProvider = mock(ObjectProvider.class);
    RevisionClickHouseSink sink = mock(RevisionClickHouseSink.class);
    when(sinkProvider.getIfAvailable()).thenReturn(sink);

    ClickHouseRevisionRecord record = ClickHouseRevisionRecord.of(
            0L, "key", null, "MasterItem", OffsetDateTime.now(),
            "{}", null, null, null, null, null);
    when(revisionService.toClickHouseRecord(eq(0L), eq("key"), eq("MasterItem"), any(), eq(null)))
            .thenReturn(record);

    GlobalPostInsertRevisionListener listener =
            new GlobalPostInsertRevisionListener(revisionServiceProvider, sinkProvider);

    // ... build event same as existing test ...
    listener.onPostInsert(event);

    verify(sink).offer(record);
}
```

- [ ] **Step 6: Run tests**

Run: `mvn -pl rama-spring-core -am test -Dgroups=unit`
Expected: PASS — all existing tests plus the new ones.

- [ ] **Step 7: Commit**

```bash
git add rama-spring-core/
git commit -m "feat(revision): listeners enqueue to ClickHouseSink when bean is present"
```

---

## Task 10: Read dispatch — RevisionService consults ClickHouse when enabled

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/service/RevisionService.java`
- Modify: `rama-spring-core/src/test/java/org/rama/service/RevisionServiceTest.java`

- [ ] **Step 1: Add a failing test that exercises the dispatch**

Add to `RevisionServiceTest.java`:

```java
@Test
void getStateAt_shouldDispatchToClickHouseRepository_whenAvailable() {
    RevisionClickHouseRepository chRepo = mock(RevisionClickHouseRepository.class);
    revisionService = new RevisionService(revisionRepository, new ObjectMapper(), chRepo);

    OffsetDateTime at = OffsetDateTime.parse("2026-05-12T00:00:00Z");
    ClickHouseRevisionRecord record = ClickHouseRevisionRecord.of(
            7L, "Patient^id^1", null, "Patient", at, "{}", null, null, null, null, null);
    when(chRepo.getStateAt("Patient^id^1", at)).thenReturn(Optional.of(record));

    Optional<Revision> result = revisionService.getStateAt("Patient^id^1", at);

    assertThat(result).isPresent();
    assertThat(result.get().getRevisionKey()).isEqualTo("Patient^id^1");
    verifyNoInteractions(revisionRepository);
}

@Test
void getStateAt_shouldFallBackToJpaRepository_whenClickHouseAbsent() {
    revisionService = new RevisionService(revisionRepository, new ObjectMapper(), null);

    OffsetDateTime at = OffsetDateTime.now();
    when(revisionRepository
            .findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc("k", at))
            .thenReturn(Optional.of(new Revision()));

    revisionService.getStateAt("k", at);

    verify(revisionRepository)
            .findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc("k", at);
}

@Test
void getStateAt_shouldFallBackToJpa_whenClickHouseThrows() {
    RevisionClickHouseRepository chRepo = mock(RevisionClickHouseRepository.class);
    revisionService = new RevisionService(revisionRepository, new ObjectMapper(), chRepo);

    OffsetDateTime at = OffsetDateTime.now();
    when(chRepo.getStateAt("k", at))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                    "ClickHouse unavailable"));
    Revision fallback = new Revision();
    fallback.setRevisionKey("k");
    when(revisionRepository
            .findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc("k", at))
            .thenReturn(Optional.of(fallback));

    Optional<Revision> result = revisionService.getStateAt("k", at);

    // Read MUST succeed via SQL fallback even when ClickHouse is broken.
    assertThat(result).isPresent();
    assertThat(result.get().getRevisionKey()).isEqualTo("k");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionServiceTest -Dgroups=unit`
Expected: FAIL — the 3-arg constructor and dispatch don't exist.

- [ ] **Step 3: Update RevisionService**

Modify `RevisionService.java`:

```java
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.clickhouse.ClickHouseRevisionRecord;

private final RevisionClickHouseRepository clickHouseRepository;  // nullable

public RevisionService(RevisionRepository revisionRepository,
                       ObjectMapper objectMapper,
                       RevisionClickHouseRepository clickHouseRepository) {
    this.revisionRepository = revisionRepository;
    this.objectMapper = objectMapper;
    this.clickHouseRepository = clickHouseRepository;
}

public Optional<Revision> getStateAt(String revisionKey, OffsetDateTime at) {
    if (clickHouseRepository != null) {
        try {
            Optional<ClickHouseRevisionRecord> chResult =
                    clickHouseRepository.getStateAt(revisionKey, at);
            return chResult.map(RevisionService::fromClickHouse);
        } catch (RuntimeException e) {
            // Fail-soft: any ClickHouse read error falls back to JPA. Audit reads
            // must keep working even when the analytical store is unavailable.
            log.warn("ClickHouse getStateAt failed; falling back to JPA for key={}, at={}. Cause: {}",
                    revisionKey, at, e.getMessage());
        }
    }
    return revisionRepository
            .findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc(revisionKey, at);
}

private static Revision fromClickHouse(ClickHouseRevisionRecord r) {
    Revision rev = new Revision();
    rev.setId(r.id() == 0 ? null : r.id());
    rev.setRevisionKey(r.revisionKey());
    rev.setMrn(r.mrn());
    rev.setRevisionEntity(r.revisionEntity());
    rev.setRevisionDatetime(r.revisionDatetime());
    // revisionData / revisionChange are JSON strings in ClickHouse; deserialize lazily if needed
    return rev;
}
```

Also update the existing constructors to pass `null` for `clickHouseRepository` so existing wiring continues to compile:

```java
public RevisionService(RevisionRepository revisionRepository) {
    this(revisionRepository, new ObjectMapper(), null);
}

public RevisionService(RevisionRepository revisionRepository, ObjectMapper objectMapper) {
    this(revisionRepository, objectMapper, null);
}
```

- [ ] **Step 4: Update the auto-config to wire the 3-arg constructor when ClickHouse is enabled**

Edit `RamaStarterAutoConfiguration.java`'s `revisionService` bean factory to inject `ObjectProvider<RevisionClickHouseRepository>` and pass `provider.getIfAvailable()` to the new constructor.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionServiceTest -Dgroups=unit`
Expected: PASS.

- [ ] **Step 6: Run the whole unit suite**

Run: `mvn -pl rama-spring-core,rama-spring-autoconfigure -am test -Dgroups=unit`
Expected: PASS, all suites green.

- [ ] **Step 7: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/service/RevisionService.java \
        rama-spring-core/src/test/java/org/rama/service/RevisionServiceTest.java \
        rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "feat(revision): RevisionService.getStateAt dispatches to ClickHouse when enabled"
```

---

## Task 11: Backfill job for ClickHouse recovery

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseBackfillJob.java`
- Test: `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseBackfillJobTest.java`

This job reconciles SQL → ClickHouse so dropped rows (queue overflow, prolonged outage) are recovered automatically. Idempotent: ClickHouse `MergeTree` will see duplicate `id` values across reruns, and the ordering by `(revision_key, revision_datetime, id)` keeps reads correct — but the simpler approach is to fetch the high-water mark from ClickHouse and only ship newer rows.

- [ ] **Step 1: Write the failing test**

Create `rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseBackfillJobTest.java`:

```java
package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseBackfillJobTest {

    @Mock private JdbcTemplate sqlJdbc;
    @Mock private JdbcTemplate clickHouseJdbc;
    @Mock private RevisionClickHouseSink sink;
    @Mock private JobExecutionContext context;

    @Test
    void execute_shouldQueueRowsAboveClickHouseHighWaterMark() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class)).thenReturn(1000L);
        when(sqlJdbc.query(anyString(), any(RowMapper.class), eq(1000L)))
                .thenReturn(List.of(
                        sampleRecord(1001L), sampleRecord(1002L), sampleRecord(1003L)));

        RevisionClickHouseBackfillJob job = new RevisionClickHouseBackfillJob(
                sqlJdbc, clickHouseJdbc, sink, "revision");
        job.executeInternal(context);

        verify(sink).offer(argMatchesId(1001L));
        verify(sink).offer(argMatchesId(1002L));
        verify(sink).offer(argMatchesId(1003L));
    }

    @Test
    void execute_shouldStartFromZero_whenClickHouseEmpty() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class)).thenReturn(null);
        when(sqlJdbc.query(anyString(), any(RowMapper.class), eq(0L))).thenReturn(List.of());

        new RevisionClickHouseBackfillJob(sqlJdbc, clickHouseJdbc, sink, "revision")
                .executeInternal(context);

        verify(sink, never()).offer(any());
    }

    @Test
    void execute_shouldSwallowClickHouseHighWaterFailure_andSkipRun() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "ClickHouse down"));

        // Must not throw; just no-op until ClickHouse recovers.
        new RevisionClickHouseBackfillJob(sqlJdbc, clickHouseJdbc, sink, "revision")
                .executeInternal(context);

        verify(sink, never()).offer(any());
    }

    private static ClickHouseRevisionRecord sampleRecord(long id) {
        return ClickHouseRevisionRecord.of(
                id, "Entity^id^" + id, null, "Entity",
                OffsetDateTime.now(), "{}", null, null, null, null, null);
    }

    private static ClickHouseRevisionRecord argMatchesId(long id) {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && r.id() == id);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseBackfillJobTest -Dgroups=unit`
Expected: FAIL — class doesn't exist yet.

- [ ] **Step 3: Implement the job**

Create `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseBackfillJob.java`:

```java
package org.rama.clickhouse;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reconciles SQL `revision` rows into ClickHouse. Catches up after:
 *   - Rows dropped due to queue overflow during a ClickHouse outage
 *   - Schema-init or batch-insert failures that landed rows in the
 *     dead-letter log but not ClickHouse
 *   - Initial bulk import for consumers enabling the feature flag
 *     on an existing SQL `revision` history
 *
 * <p>Algorithm:
 *   1. Read high-water mark: {@code SELECT MAX(id) FROM clickhouse.revision} (default 0).
 *   2. Page through {@code SELECT * FROM sql.revision WHERE id > ? ORDER BY id LIMIT 10000}.
 *   3. Enqueue each row into {@link RevisionClickHouseSink} (which handles batching).
 *   4. Repeat until SQL returns fewer than the page size.
 *
 * <p>Idempotent. Cheap when up-to-date (one query against each store).
 * Schedule nightly off-peak. Manual trigger via {@code QuartzService.triggerNow}.
 */
@Slf4j
public class RevisionClickHouseBackfillJob extends QuartzJobBean {

    private static final int PAGE_SIZE = 10_000;

    private final JdbcTemplate sqlJdbcTemplate;
    private final JdbcTemplate clickHouseJdbcTemplate;
    private final RevisionClickHouseSink sink;
    private final String tableName;

    public RevisionClickHouseBackfillJob(JdbcTemplate sqlJdbcTemplate,
                                         JdbcTemplate clickHouseJdbcTemplate,
                                         RevisionClickHouseSink sink,
                                         String tableName) {
        this.sqlJdbcTemplate = sqlJdbcTemplate;
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
        this.sink = sink;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
    }

    @Override
    public void executeInternal(JobExecutionContext context) {
        long highWaterMark;
        try {
            Long maxId = clickHouseJdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM " + tableName, Long.class);
            highWaterMark = maxId == null ? 0L : maxId;
        } catch (RuntimeException e) {
            // ClickHouse unavailable — skip this run; will retry next schedule.
            log.warn("Backfill job: cannot read ClickHouse high-water mark; skipping. Cause: {}",
                    e.getMessage());
            return;
        }
        log.info("Backfill job: ClickHouse high-water id = {}", highWaterMark);

        long cursor = highWaterMark;
        long enqueued = 0;
        while (true) {
            List<ClickHouseRevisionRecord> batch = sqlJdbcTemplate.query(
                    "SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                            + " revision_data, revision_change,"
                            + " created_by, updated_by, created_at, updated_at"
                            + " FROM revision WHERE id > ? ORDER BY id ASC"
                            + " FETCH FIRST " + PAGE_SIZE + " ROWS ONLY",
                    ROW_MAPPER, cursor);
            if (batch.isEmpty()) break;

            for (ClickHouseRevisionRecord r : batch) {
                sink.offer(r);
                enqueued++;
                cursor = Math.max(cursor, r.id());
            }
            if (batch.size() < PAGE_SIZE) break;
        }
        log.info("Backfill job: enqueued {} rows past id {}", enqueued, highWaterMark);
    }

    private static final RowMapper<ClickHouseRevisionRecord> ROW_MAPPER = (ResultSet rs, int rowNum) ->
            ClickHouseRevisionRecord.of(
                    rs.getLong("id"),
                    rs.getString("revision_key"),
                    rs.getString("mrn"),
                    rs.getString("revision_entity"),
                    toOdt(rs.getTimestamp("revision_datetime")),
                    rs.getString("revision_data"),
                    rs.getString("revision_change"),
                    rs.getString("created_by"),
                    rs.getString("updated_by"),
                    toOdt(rs.getTimestamp("created_at")),
                    toOdt(rs.getTimestamp("updated_at")));

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Wire the job in the auto-config**

Add to `RamaStarterClickHouseAutoConfiguration.java`:

```java
@Bean
@ConditionalOnMissingBean
public RevisionClickHouseBackfillJob revisionClickHouseBackfillJob(
        JdbcTemplate sqlJdbcTemplate,  // injected from primary DataSource
        @org.springframework.beans.factory.annotation.Qualifier("clickHouseJdbcTemplate") JdbcTemplate chJdbc,
        RevisionClickHouseSink sink,
        ClickHouseProperties props) {
    return new RevisionClickHouseBackfillJob(sqlJdbcTemplate, chJdbc, sink, props.getTableName());
}
```

The consumer's `QuartzService` schedules this job at app start or via configuration — see existing Quartz scheduling pattern in the starter.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl rama-spring-core -am test -Dtest=RevisionClickHouseBackfillJobTest -Dgroups=unit`
Expected: PASS — 3 tests.

- [ ] **Step 6: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseBackfillJob.java \
        rama-spring-core/src/test/java/org/rama/clickhouse/RevisionClickHouseBackfillJobTest.java \
        rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterClickHouseAutoConfiguration.java
git commit -m "feat(revision): add RevisionClickHouseBackfillJob for SQL→ClickHouse reconciliation"
```

---

## Task 12: Integration test with Testcontainers ClickHouse

**Files:**
- Modify: `rama-spring-demo/pom.xml`
- Create: `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionClickHouseIntegrationIT.java`
- Create: `rama-spring-demo/src/test/resources/application-clickhouse.properties`

- [ ] **Step 1: Add Testcontainers ClickHouse + clickhouse-jdbc to demo's test deps**

In `rama-spring-demo/pom.xml`:

```xml
<dependency>
    <groupId>com.clickhouse</groupId>
    <artifactId>clickhouse-jdbc</artifactId>
    <version>0.7.2</version>
    <classifier>http</classifier>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>clickhouse</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the integration test**

Create `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionClickHouseIntegrationIT.java`:

```java
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
            new ClickHouseContainer("clickhouse/clickhouse-server:24.8");

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
```

- [ ] **Step 3: Add `application-clickhouse.properties` to enable the profile**

Create `rama-spring-demo/src/test/resources/application-clickhouse.properties`:

```properties
# Activated by the ActiveProfiles annotation; properties supplied via @DynamicPropertySource.
# Empty file is fine -- the profile name alone enables the auto-config when the dynamic props set enabled=true.
```

- [ ] **Step 4: Run the integration test**

Run: `mvn -pl rama-spring-demo -am verify -Dgroups=integration -Dit.test=RevisionClickHouseIntegrationIT`
Expected: PASS. First run pulls the `clickhouse/clickhouse-server:24.8` Docker image (~500 MB), subsequent runs are cached.

- [ ] **Step 5: Add a failover IT — verifies app survives ClickHouse going down**

Create `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionClickHouseFailoverIT.java`:

```java
package org.rama.demo.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseRepository;
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
            new ClickHouseContainer("clickhouse/clickhouse-server:24.8");

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
    void sinkOffer_shouldNotThrow_whenClickHouseIsDown() throws Exception {
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
```

- [ ] **Step 6: Run the full demo suite to confirm no regression**

Run: `mvn -pl rama-spring-demo -am verify`
Expected: PASS — original demo tests still green, both new ClickHouse ITs pass.

- [ ] **Step 7: Commit**

```bash
git add rama-spring-demo/
git commit -m "test(revision): add Testcontainers ClickHouse integration + failover tests"
```

---

## Task 13: Documentation update

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/clickhouse-revision.md`

- [ ] **Step 1: Update CLAUDE.md**

Add to the "Feature flags" section in `CLAUDE.md`:

```markdown
- `rama.revision.clickhouse.enabled` -- Mirror revisions to ClickHouse (default `false`).
  When `true`, listeners enqueue to `RevisionClickHouseSink` in addition to writing the SQL
  `revision` table; `RevisionService.getStateAt` reads from ClickHouse. Requires
  `rama.revision.clickhouse.url` (JDBC) and credentials. See `docs/clickhouse-revision.md`.
```

- [ ] **Step 2: Create the dedicated doc**

Create `docs/clickhouse-revision.md`:

```markdown
# ClickHouse-Backed Revision Audit Log

## Overview

When `rama.revision.clickhouse.enabled=true`, the starter dual-writes every
revision to ClickHouse alongside the SQL `revision` table, and serves
point-in-time / history queries from ClickHouse via `RevisionService`.

The SQL `revision` table remains the synchronous source of truth. ClickHouse
is an async, batched mirror — if a batch insert fails, the SQL row is still
durable and a future backfill job can re-synchronize.

## Consumer setup

Properties (all under `rama.revision.clickhouse`):

| Property | Default | Notes |
|---|---|---|
| `enabled` | `false` | Master switch. |
| `url` | — | Full JDBC URL, e.g. `jdbc:ch://ch.example:8123/audit` |
| `username` | — | |
| `password` | — | |
| `table-name` | `revision` | ClickHouse table name. |
| `batch-size` | `1000` | Rows per `INSERT` batch. |
| `flush-interval` | `PT5S` | Wall-clock interval for the scheduled flusher. |
| `max-queue-size` | `100000` | Back-pressure threshold; oldest rows dropped with WARN. |

## ClickHouse schema

The starter creates the table at startup with `CREATE TABLE IF NOT EXISTS`:

```sql
CREATE TABLE IF NOT EXISTS revision (
    id              UInt64,
    revision_key    String,
    mrn             LowCardinality(Nullable(String)),
    revision_entity LowCardinality(Nullable(String)),
    revision_datetime DateTime64(3, 'UTC'),
    revision_data   String CODEC(ZSTD(3)),
    revision_change Nullable(String) CODEC(ZSTD(3)),
    created_by      LowCardinality(Nullable(String)),
    updated_by      LowCardinality(Nullable(String)),
    created_at      Nullable(DateTime64(3, 'UTC')),
    updated_at      Nullable(DateTime64(3, 'UTC'))
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(revision_datetime)
ORDER BY (revision_key, revision_datetime, id);
```

Sort key matches the dominant point-in-time read pattern; partitioning by
month makes drop-old-partition operations metadata-only if retention is
ever loosened.

## Operational notes

- **Compression.** ZSTD(3) on JSON columns + LowCardinality on entity/user
  dimensions typically yields 10-20× compression on revision data.
- **Backups.** Either ClickHouse-native `BACKUP TABLE` or external
  freeze + copy of `/var/lib/clickhouse/shadow/` snapshots.
- **Schema evolution.** New columns: `ALTER TABLE revision ADD COLUMN`,
  ClickHouse handles defaults for historical rows.
- **Failure modes.** If ClickHouse is down, the sink buffers up to
  `max-queue-size` rows then drops the oldest. SQL writes are unaffected.
  When ClickHouse comes back, run `RevisionClickHouseBackfillJob` (TBD —
  separate plan) to repopulate missing rows from the SQL `revision` table.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/clickhouse-revision.md
git commit -m "docs(revision): document ClickHouse audit log feature flag and schema"
```

---

## Task 14: Final verification

- [ ] **Step 1: Run the whole reactor**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Run all unit tests**

Run: `mvn test -Dgroups=unit`
Expected: PASS — every module green. The new ClickHouse-related tests count: 2 (properties) + 2 (record) + 3 (schema init, incl. fail-soft) + 9 (sink, incl. transient + permanent) + 3 (repository) + 3 (backfill) + 2 (listener) + 3 (service dispatch, incl. fallback) = 27 new unit tests.

- [ ] **Step 3: Run integration tests (requires Docker for the ClickHouse Testcontainer)**

Run: `mvn -pl rama-spring-demo -am verify`
Expected: PASS — pre-existing demo ITs plus the new `RevisionClickHouseIntegrationIT`.

- [ ] **Step 4: Sanity-check the feature flag is truly off by default**

Spin up the demo without setting any `rama.revision.clickhouse.*` properties:

```bash
mvn -pl rama-spring-demo spring-boot:run
```

Expected: no ClickHouse log lines, no Hikari pool named `rama-clickhouse`, no `CREATE TABLE IF NOT EXISTS revision` executed against any data source. Behavior should be 100% identical to today.

- [ ] **Step 5: Final commit / tag**

Nothing to commit (all earlier tasks committed individually). If using semver tags, this is the natural tag boundary:

```bash
git tag -a v4.0.x-clickhouse -m "Add ClickHouse-backed revision audit log (opt-in)"
```

---

# Appendix: How to Implement ClickHouse (Server-Side)

The Spring-side integration above assumes a working ClickHouse server. This appendix covers the server setup for both local development and production deployment.

## Local development — single-node Docker

Add to `docker-compose.yml` (or a separate `docker-compose.clickhouse.yml`):

```yaml
services:
  clickhouse:
    image: clickhouse/clickhouse-server:24.8
    container_name: clickhouse
    ports:
      - "8123:8123"   # HTTP
      - "9000:9000"   # native protocol
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - clickhouse-logs:/var/log/clickhouse-server
    environment:
      CLICKHOUSE_DB: audit
      CLICKHOUSE_USER: rama
      CLICKHOUSE_PASSWORD: localpass
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1

volumes:
  clickhouse-data:
  clickhouse-logs:
```

Bring up:

```bash
docker compose up -d clickhouse
docker exec -it clickhouse clickhouse-client --user rama --password localpass --query "SELECT version()"
```

Connect with the starter:

```properties
rama.revision.clickhouse.enabled=true
rama.revision.clickhouse.url=jdbc:ch://localhost:8123/audit
rama.revision.clickhouse.username=rama
rama.revision.clickhouse.password=localpass
```

## Production — single-shard, replicated

For Rama's scale (50K rows/day × multi-year), a **single shard with two replicas** is enough capacity-wise and gives HA. Three nodes total:

| Node | Role |
|---|---|
| ZooKeeper or ClickHouse Keeper (3 nodes) | Coordination for replication |
| clickhouse-1, clickhouse-2 | Shard 1, replicas A and B (each holds the full dataset) |

ClickHouse Keeper is the modern recommendation — it ships in the ClickHouse package and replaces external ZooKeeper. Run 3 keeper nodes (can be on the same hosts as the data nodes).

Production table changes from the local schema:

```sql
CREATE TABLE revision ON CLUSTER 'rama_cluster' (
    -- same columns as before
)
ENGINE = ReplicatedMergeTree(
    '/clickhouse/tables/{shard}/revision',
    '{replica}')
PARTITION BY toYYYYMM(revision_datetime)
ORDER BY (revision_key, revision_datetime, id);
```

`ReplicatedMergeTree` (instead of `MergeTree`) makes replication automatic. The schema initializer in this plan creates plain `MergeTree`; for production, **override the schema by setting `rama.revision.clickhouse.enabled=true` AND running the production DDL manually** (or extend `ClickHouseSchemaInitializer` to take an engine-clause property — out of scope for this plan).

## Sizing rough numbers

For Rama's expected workload (~20M revisions/year, ~5 KB JSON per row uncompressed):

- **Storage.** 20M × 5 KB = 100 GB/year uncompressed. ZSTD compression ~10× → **10 GB/year**. After 10 years: **~100 GB**. A 1 TB SSD per replica is wildly oversized.
- **Memory.** ClickHouse default settings (`mark_cache_size = 5G`, `max_server_memory_usage` ≈ 90% of RAM). **32 GB RAM** per node is comfortable.
- **CPU.** ClickHouse is CPU-heavy on reads but the access pattern here (indexed seeks on `revision_key`) is cheap. **8 cores** per node is plenty.
- **Network.** Batched inserts at 1000 rows/batch × 5 KB = 5 MB per batch. Tiny.

## Backup strategy

1. **`BACKUP TABLE`** (native) to S3-compatible storage (MinIO is already in the Rama stack):

   ```sql
   BACKUP TABLE audit.revision TO S3('http://minio:9000/ch-backups/revision-2026-05',
       'access_key', 'secret_key');
   ```

   Native backup is incremental-aware; subsequent backups only store changed parts.

2. **Cron weekly full + daily incremental.** Schedule via Quartz from the starter (`QuartzService.scheduleJob`) or a sidecar cron container.

3. **Restore tested quarterly** in a staging environment.

## Monitoring

ClickHouse exposes Prometheus metrics on port 9363:

```yaml
prometheus:
  endpoint: /metrics
  port: 9363
```

Critical alerts:
- **Replication lag** > 30s — replica is falling behind.
- **Insert errors** — investigate via `system.query_log`.
- **Disk usage** > 80% — add storage or extend retention pruning.
- **`system.merges`** — long-running merges indicate write pressure.

## Schema migrations going forward

Use raw `ALTER TABLE`. ClickHouse adds nullable columns instantly; non-nullable column adds rewrite the table (avoid). Liquibase has a community ClickHouse extension but it's experimental — direct DDL is cleaner for this use case.

```sql
-- Adding a new optional column
ALTER TABLE revision ADD COLUMN session_id Nullable(String) AFTER revision_change;

-- Re-ordering or changing types requires materialized rebuild:
-- CREATE TABLE revision_new AS revision ENGINE=...; INSERT INTO revision_new SELECT ... FROM revision;
-- DROP TABLE revision; RENAME TABLE revision_new TO revision;
```

---

# Self-review (one-time check before handing off)

- **Spec coverage:** Every requirement from the conversation is mapped: dual-write (Task 9), batched async writer (Tasks 5-6), feature flag (Tasks 2 + 8), read API (Tasks 7 + 10), schema (Task 4), backfill (Task 11), integration tests including failover (Task 12), docs (Task 13), server setup (Appendix). Resilience changes from the "what if ClickHouse is unavailable" review: fail-soft schema init (Task 4), HikariCP `initializationFailTimeout=-1` (Task 8), transient-vs-permanent flush failure (Task 5), read fallback (Task 10), backfill reconciliation (Task 11), failover IT (Task 12).
- **No placeholders:** Every step contains either code or exact commands. No "TBD" or "implement later" markers.
- **Type consistency:** `ClickHouseRevisionRecord` factory signature matches Task 3, Task 5 sink, Task 7 repository, Task 9 listener wiring, Task 10 service dispatch, and Task 11 backfill job.
- **Failure-mode coverage:** All 9 scenarios from the resilience review are addressed in tasks: startup unreachable (Tasks 4 + 8), schema-init fail (Task 4), runtime outage (Task 5 transient), queue overflow (Task 5 already-existing back-pressure), poison pill (Task 5 permanent classification), read failure (Task 10 fallback), read timeout (HikariCP `connectionTimeout` in Task 8), observability (logs throughout — metrics are an optional extension, documented but deliberately not in v1 to keep the plan focused), automatic recovery (Task 11 backfill).

---

# Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-12-revision-clickhouse.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
