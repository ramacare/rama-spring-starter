# ClickHouse Audit Log via Transactional Outbox — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the revision audit log to ClickHouse as the primary store. The SQL `revision` table is removed from the starter. A generic `system_buffer` outbox in SQL provides synchronous transactional durability between the entity transaction and the asynchronous ClickHouse write.

**Architecture:**

```
entity tx:
    listener afterCommit
       → SystemBufferService.enqueue(buffer_type, payload, target)
       → single SQL INSERT into system_buffer (durable, transactional)

SystemBufferDrainJob (Quartz, every 30s):
    SELECT FROM system_buffer WHERE buffer_type = ? ORDER BY id LIMIT 1000
    → dispatcher.dispatch(payload) per buffer_type
    → success → DELETE drained rows
    → failure → UPDATE attempt_count, last_error, last_attempt_at

Read path:
    RevisionService.getStateAt → ClickHouse FINAL query (no JPA fallback)
```

**Tech stack:** Spring Boot 4.0.3, Quartz, `com.clickhouse:clickhouse-jdbc:0.7.2`, ReplacingMergeTree on ClickHouse, Jackson 3.

---

## File Structure

**Generic outbox (rama-spring-core):**
- `entity/system/SystemBuffer.java` — JPA entity for the buffer row.
- `repository/system/SystemBufferRepository.java` — `BaseRepository<SystemBuffer, Long>` + pageable query by `buffer_type`.
- `service/system/SystemBufferService.java` — `enqueue(buffer_type, payload, target)` API.
- `service/system/SystemBufferDispatcher.java` — interface; `bufferType()` + `dispatch(List<SystemBuffer>)`.
- `job/system/SystemBufferDrainJob.java` — Quartz `QuartzJobBean`; drains per-type using registered dispatchers.

**ClickHouse adapter (rama-spring-core):**
- `clickhouse/ClickHouseProperties.java` (moves from autoconfigure or stays there — pragmatic choice).
- `clickhouse/ClickHouseSchemaInitializer.java` — runs `CREATE TABLE IF NOT EXISTS` with the new ReplacingMergeTree schema.
- `clickhouse/ClickHouseRevisionRecord.java` — wire shape (no surrogate `id`; includes `ingested_at` filled by ClickHouse default).
- `clickhouse/RevisionClickHouseRepository.java` — read queries with `FINAL`, no `id > 0` filter.
- `clickhouse/ClickHouseRevisionDispatcher.java` — `SystemBufferDispatcher` impl for `buffer_type = "revision"`. Bulk-INSERTs to ClickHouse.

**Revision-specific changes (rama-spring-core):**
- `entity/Revision.java` — **convert to POJO** (remove `@Entity`, `@Table`, JPA annotations); keep Lombok `@Data` + fields.
- `repository/revision/RevisionRepository.java` — **delete** (no SQL revision table anymore).
- `service/RevisionService.java` — rework `saveRevision` to call `SystemBufferService.enqueue`; rework `getStateAt` to query ClickHouse only.
- `listener/global/GlobalPostInsertRevisionListener.java` — simplify; just call `revisionService.saveRevision`.
- `listener/global/GlobalPostUpdateRevisionListener.java` — same.
- `job/revision/RevisionLegacyMigrationJob.java` — one-shot reader of existing SQL `revision` (if present) → ClickHouse via native SQL. Replaces the old `RevisionClickHouseBackfillJob`.

**Liquibase (rama-spring-autoconfigure):**
- `db/changelog/rama-spring-system.changelog.yaml` — **remove** changesets `006-revision` and `007-revision-datetime-indexes`. **Add** `008-system-buffer`.

**Auto-config (rama-spring-autoconfigure):**
- `autoconfigure/RamaStarterClickHouseAutoConfiguration.java` — bean wiring. ClickHouse DataSource, JdbcTemplate, schema initializer, dispatcher, drain job. All gated on `rama.revision.clickhouse.enabled=true`.
- `autoconfigure/RamaStarterAutoConfiguration.java` — wire `SystemBufferService` (always enabled if JPA is on). Drop the dual-write listener wiring.

**Tests (rama-spring-core):**
- `test/.../SystemBufferServiceTest.java`
- `test/.../SystemBufferDrainJobTest.java`
- `test/.../ClickHouseRevisionDispatcherTest.java`
- `test/.../ClickHouseSchemaInitializerTest.java` (new schema)
- `test/.../RevisionClickHouseRepositoryTest.java` (FINAL queries)
- Updated `RevisionServiceTest`, `RevisionListenerTest`

**Demo (rama-spring-demo):**
- Add Testcontainers ClickHouse IT (outbox → drain → CH round-trip).

---

## Task 1: Liquibase + ClickHouse client dependency

**Files:**
- Modify: `rama-spring-autoconfigure/src/main/resources/db/changelog/rama-spring-system.changelog.yaml`
- Modify: `rama-spring-core/pom.xml`
- Create: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/ClickHouseProperties.java`
- Create: `rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/ClickHousePropertiesTest.java`

### Step 1.1: Remove SQL revision changesets

In `rama-spring-system.changelog.yaml`, **delete** the two changeset blocks:
- `id: rama-spring-system-006-revision` (creates the revision table)
- `id: rama-spring-system-007-revision-datetime-indexes` (adds indexes)

Liquibase doesn't undo already-applied changesets; existing consumers keep their tables. New consumers don't get them.

### Step 1.2: Add system_buffer changeset

Add at the end of the changelog (after the last changeset):

```yaml
- changeSet:
    id: rama-spring-system-008-system-buffer
    author: claude
    preConditions:
    - onFail: MARK_RAN
    - onError: MARK_RAN
    - not:
        tableExists:
          tableName: system_buffer
    changes:
    - createTable:
        tableName: system_buffer
        columns:
        - column:
            name: id
            type: BIGINT
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column:
            name: buffer_type
            type: VARCHAR(100)
            constraints:
              nullable: false
        - column:
            name: payload
            type: ${clobType}
            constraints:
              nullable: false
        - column:
            name: target
            type: VARCHAR(255)
        - column:
            name: attempt_count
            type: INT
            defaultValueNumeric: 0
            constraints:
              nullable: false
        - column:
            name: last_error
            type: VARCHAR(2000)
        - column:
            name: last_attempt_at
            type: ${timestampType}
        - column:
            name: created_at
            type: ${timestampType}
            constraints:
              nullable: false
        - column:
            name: created_by
            type: VARCHAR(200)
    - createIndex:
        tableName: system_buffer
        indexName: ix_system_buffer__type_id
        columns:
        - column:
            name: buffer_type
        - column:
            name: id
```

### Step 1.3: Add `clickhouse-jdbc` to rama-spring-core/pom.xml

Add after `spring-boot-starter-data-jpa`:

```xml
<dependency>
    <groupId>com.clickhouse</groupId>
    <artifactId>clickhouse-jdbc</artifactId>
    <version>0.7.2</version>
    <classifier>http</classifier>
    <optional>true</optional>
</dependency>
```

### Step 1.4: Create ClickHouseProperties + test

Use the same shape as the previous branch's `ClickHouseProperties` (in `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/`):

```java
@Data
@ConfigurationProperties(prefix = "rama.revision.clickhouse")
public class ClickHouseProperties {
    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private String tableName = "revision";
    private int drainBatchSize = 1000;
    private Duration drainInterval = Duration.ofSeconds(30);
    // (removed: batchSize, flushInterval, maxQueueSize — no in-memory sink anymore)
}
```

Test verifies binding + defaults (2 tests).

Add `spring-boot-starter-test` to `rama-spring-autoconfigure/pom.xml` test scope (was added in the previous branch).

### Step 1.5: Verify

```bash
mvn clean install -DskipTests -q
mvn -pl rama-spring-autoconfigure -am test -Dgroups=unit -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ClickHousePropertiesTest
mvn -pl rama-spring-demo -am verify  # 14 demo ITs must still pass — no functional change yet
```

### Step 1.6: Commit

```bash
git add rama-spring-autoconfigure/src/main/resources/db/changelog/rama-spring-system.changelog.yaml \
        rama-spring-core/pom.xml \
        rama-spring-autoconfigure/pom.xml \
        rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/ClickHouseProperties.java \
        rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/ClickHousePropertiesTest.java
git commit -m "refactor(revision): remove SQL revision migrations; add system_buffer + ClickHouse client deps"
```

---

## Task 2: Generic outbox (SystemBuffer entity + service + dispatcher + drain job)

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/entity/system/SystemBuffer.java`
- Create: `rama-spring-core/src/main/java/org/rama/repository/system/SystemBufferRepository.java`
- Create: `rama-spring-core/src/main/java/org/rama/service/system/SystemBufferService.java`
- Create: `rama-spring-core/src/main/java/org/rama/service/system/SystemBufferDispatcher.java`
- Create: `rama-spring-core/src/main/java/org/rama/job/system/SystemBufferDrainJob.java`
- Plus tests for each.

### Step 2.1: SystemBuffer entity

```java
package org.rama.entity.system;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "system_buffer")
@Data
@NoArgsConstructor
public class SystemBuffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @Column(nullable = false, length = 100)
    private String bufferType;

    @Column(nullable = false, columnDefinition = "clob")
    private String payload;

    @Column(length = 255)
    private String target;

    @Column(nullable = false)
    private int attemptCount = 0;

    @Column(length = 2000)
    private String lastError;

    private OffsetDateTime lastAttemptAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(length = 200, updatable = false)
    private String createdBy;
}
```

### Step 2.2: SystemBufferRepository

```java
package org.rama.repository.system;

import org.rama.entity.system.SystemBuffer;
import org.rama.repository.BaseRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface SystemBufferRepository extends BaseRepository<SystemBuffer, Long> {
    List<SystemBuffer> findByBufferTypeOrderByIdAsc(String bufferType, Pageable pageable);
    long countByBufferType(String bufferType);
}
```

### Step 2.3: SystemBufferService

```java
package org.rama.service.system;

import lombok.RequiredArgsConstructor;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class SystemBufferService {

    private final SystemBufferRepository repository;

    /**
     * Enqueue a payload for asynchronous dispatch. MUST be called from within
     * an entity transaction (e.g. listener afterCommit synchronization). The
     * INSERT participates in the calling transaction — if the transaction rolls
     * back, the buffer row is rolled back too, preventing phantom dispatches.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SystemBuffer enqueue(String bufferType, String payload, String target) {
        SystemBuffer row = new SystemBuffer();
        row.setBufferType(bufferType);
        row.setPayload(payload);
        row.setTarget(target);
        return repository.save(row);
    }
}
```

### Step 2.4: SystemBufferDispatcher interface

```java
package org.rama.service.system;

import org.rama.entity.system.SystemBuffer;

import java.util.List;

/**
 * Dispatches one buffer_type to its target system. Implementations register
 * themselves as Spring beans; the drain job picks the right one by matching
 * {@link #bufferType()}.
 */
public interface SystemBufferDispatcher {

    /** The buffer_type this dispatcher handles (e.g. "revision"). */
    String bufferType();

    /**
     * Dispatch a batch. On success, the drain job deletes the rows. On any
     * exception, the drain job updates the rows' attempt_count and last_error
     * but does NOT delete them — they're retried next run.
     */
    void dispatch(List<SystemBuffer> batch);
}
```

### Step 2.5: SystemBufferDrainJob

```java
package org.rama.job.system;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic drain for {@code system_buffer}. Per Quartz invocation:
 *   1. Group registered dispatchers by buffer_type.
 *   2. For each buffer_type:
 *      a. Page rows from system_buffer (ORDER BY id LIMIT batchSize)
 *      b. dispatcher.dispatch(batch)
 *      c. On success → DELETE drained rows
 *      d. On exception → UPDATE attempt_count / last_error / last_attempt_at
 *
 * <p>Schedule frequently (every 30s) so the buffer stays small. During
 * dispatcher-side outages the buffer accumulates; recovery is automatic.
 */
@Slf4j
public class SystemBufferDrainJob extends QuartzJobBean {

    public static final String KEY_BATCH_SIZE = "batchSize";

    private final SystemBufferRepository repository;
    private final Map<String, SystemBufferDispatcher> dispatchersByType;

    public SystemBufferDrainJob(SystemBufferRepository repository,
                                List<SystemBufferDispatcher> dispatchers) {
        this.repository = repository;
        this.dispatchersByType = dispatchers.stream()
                .collect(Collectors.toMap(SystemBufferDispatcher::bufferType, d -> d));
    }

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        int batchSize = context.getMergedJobDataMap().containsKey(KEY_BATCH_SIZE)
                ? context.getMergedJobDataMap().getIntValue(KEY_BATCH_SIZE) : 1000;

        for (Map.Entry<String, SystemBufferDispatcher> e : dispatchersByType.entrySet()) {
            String type = e.getKey();
            SystemBufferDispatcher dispatcher = e.getValue();
            drainOne(type, dispatcher, batchSize);
        }
    }

    void drainOne(String bufferType, SystemBufferDispatcher dispatcher, int batchSize) {
        List<SystemBuffer> batch = repository.findByBufferTypeOrderByIdAsc(
                bufferType, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        try {
            dispatcher.dispatch(batch);
            repository.deleteAllInBatch(batch);
            log.info("Drained {} system_buffer rows for type={}", batch.size(), bufferType);
        } catch (RuntimeException ex) {
            log.warn("Drain failed for type={}; will retry next run. Cause: {}",
                    bufferType, ex.getMessage());
            // Update attempt tracking. Do this in a separate JdbcTemplate UPDATE or
            // by calling repository.saveAll with mutated fields. Don't delete.
            OffsetDateTime now = OffsetDateTime.now();
            for (SystemBuffer row : batch) {
                row.setAttemptCount(row.getAttemptCount() + 1);
                row.setLastError(truncate(ex.getMessage(), 2000));
                row.setLastAttemptAt(now);
            }
            repository.saveAll(batch);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

### Step 2.6: Tests

- `SystemBufferServiceTest` — verifies `enqueue` saves a row with the right fields. Mockito mock the repository.
- `SystemBufferDrainJobTest` — 4 tests:
  - Empty buffer → no dispatch call, no delete.
  - Non-empty buffer → dispatch called with the batch, deleteAllInBatch called.
  - Dispatcher throws → no delete, attempt_count incremented, lastError set, saveAll called.
  - Multiple buffer_types → each dispatcher called with its own batch.

### Step 2.7: Auto-config wiring

In `RamaStarterAutoConfiguration` (the existing JPA-scoped auto-config), wire:

```java
@Bean
@ConditionalOnMissingBean
public SystemBufferService systemBufferService(SystemBufferRepository repo) {
    return new SystemBufferService(repo);
}

@Bean
@ConditionalOnMissingBean
public SystemBufferDrainJob systemBufferDrainJob(
        SystemBufferRepository repo,
        ObjectProvider<SystemBufferDispatcher> dispatchers) {
    return new SystemBufferDrainJob(repo, dispatchers.stream().toList());
}
```

The `SystemBufferRepository` is auto-discovered by Spring Data JPA's `@EnableJpaRepositories` scan (already configured in the starter for `org.rama.repository`).

Add `org.rama.entity.system` to the EntityScan basePackages if not already covered.

### Step 2.8: Commit

```bash
git add rama-spring-core/src/main/java/org/rama/entity/system/ \
        rama-spring-core/src/main/java/org/rama/repository/system/ \
        rama-spring-core/src/main/java/org/rama/service/system/ \
        rama-spring-core/src/main/java/org/rama/job/system/ \
        rama-spring-core/src/test/java/org/rama/service/system/ \
        rama-spring-core/src/test/java/org/rama/job/system/ \
        rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "feat(outbox): add generic system_buffer outbox (SystemBufferService + DrainJob + Dispatcher SPI)"
```

---

## Task 3: ClickHouse schema + record + repository

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseSchemaInitializer.java`
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseRevisionRecord.java`
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/RevisionClickHouseRepository.java`
- Plus tests.

### Step 3.1: ClickHouseRevisionRecord (no surrogate id)

```java
package org.rama.clickhouse;

import java.time.OffsetDateTime;

/**
 * Wire shape for one revision row going to ClickHouse. No surrogate `id` —
 * natural identity is (revisionKey, revisionDatetime). ReplacingMergeTree
 * on ClickHouse uses `ingested_at` as the dedup version; the dispatcher
 * doesn't set it (relies on the ClickHouse column default `now64(3)`).
 */
public record ClickHouseRevisionRecord(
        String revisionKey,
        String revisionEntity,
        String mrn,
        OffsetDateTime revisionDatetime,
        String revisionData,
        String revisionChange,
        String createdBy,
        String updatedBy) {
}
```

### Step 3.2: ClickHouseSchemaInitializer (new schema)

```java
package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class ClickHouseSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;

    @Override
    public void run(ApplicationArguments args) {
        String safe = safeIdent(tableName);
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    revision_key       String,
                    revision_datetime  DateTime64(3, 'UTC'),
                    revision_entity    LowCardinality(String),
                    mrn                LowCardinality(Nullable(String)),
                    revision_data      String CODEC(ZSTD(3)),
                    revision_change    Nullable(String) CODEC(ZSTD(3)),
                    created_by         LowCardinality(Nullable(String)),
                    updated_by         LowCardinality(Nullable(String)),
                    ingested_at        DateTime64(3, 'UTC') DEFAULT now64(3),
                    INDEX idx_mrn        mrn             TYPE bloom_filter GRANULARITY 4,
                    INDEX idx_entity     revision_entity TYPE bloom_filter GRANULARITY 4,
                    INDEX idx_created_by created_by      TYPE bloom_filter GRANULARITY 4
                )
                ENGINE = ReplacingMergeTree(ingested_at)
                PARTITION BY toYYYYMM(revision_datetime)
                ORDER BY (revision_key, revision_datetime)
                SETTINGS index_granularity = 8192
                """.formatted(safe);

        log.info("Initializing ClickHouse table {}", tableName);
        try {
            clickHouseJdbcTemplate.execute(ddl);
        } catch (RuntimeException e) {
            log.error("Failed to initialize ClickHouse table {} — continuing. Cause: {}",
                    tableName, e.getMessage());
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

### Step 3.3: RevisionClickHouseRepository (FINAL, no id filter)

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

    private static final String COLUMNS =
            "revision_key, revision_datetime, revision_entity, mrn,"
                    + " revision_data, revision_change,"
                    + " created_by, updated_by, ingested_at";

    public Optional<ClickHouseRevisionRecord> getStateAt(String revisionKey, OffsetDateTime at) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE revision_key = ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    sql, ROW_MAPPER, revisionKey, Timestamp.from(at.toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ClickHouseRevisionRecord> findHistory(String revisionKey) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE revision_key = ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, revisionKey);
    }

    public List<ClickHouseRevisionRecord> findByMrn(String mrn, OffsetDateTime from, OffsetDateTime to) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE mrn = ? AND revision_datetime >= ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, mrn,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    private static ClickHouseRevisionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ClickHouseRevisionRecord(
                rs.getString("revision_key"),
                rs.getString("revision_entity"),
                rs.getString("mrn"),
                toOdt(rs.getTimestamp("revision_datetime")),
                rs.getString("revision_data"),
                rs.getString("revision_change"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
        // ingested_at is read but not surfaced — internal to dedup mechanics.
    }

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
```

### Step 3.4: Tests

- `ClickHouseSchemaInitializerTest` — 3 tests (DDL contains correct engine/sort/skip-index/codec keywords, configurable table name, fail-soft on connection error).
- `RevisionClickHouseRepositoryTest` — 3 tests (getStateAt-present, getStateAt-empty, findHistory).

### Step 3.5: Commit

```bash
git add rama-spring-core/src/main/java/org/rama/clickhouse/ \
        rama-spring-core/src/test/java/org/rama/clickhouse/
git commit -m "feat(revision): ClickHouse schema (ReplacingMergeTree) + record + repository"
```

---

## Task 4: Revision wiring — service rework + dispatcher + listener simplification

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/entity/Revision.java` (convert to POJO)
- **Delete**: `rama-spring-core/src/main/java/org/rama/repository/revision/RevisionRepository.java`
- Modify: `rama-spring-core/src/main/java/org/rama/service/RevisionService.java`
- Modify: `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostInsertRevisionListener.java`
- Modify: `rama-spring-core/src/main/java/org/rama/listener/global/GlobalPostUpdateRevisionListener.java`
- Create: `rama-spring-core/src/main/java/org/rama/clickhouse/ClickHouseRevisionDispatcher.java`
- Plus tests + auto-config updates.

### Step 4.1: Convert Revision to POJO

Remove `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@Convert`, `@Nationalized`, `@Embedded`. Keep `@Data`, `@NoArgsConstructor`. Remove the `Auditable` interface (since it's no longer JPA-managed). Drop `id`, `userstampField`, `timestampField` fields (those were SQL audit columns; the buffer carries equivalent metadata).

Final POJO:
```java
package org.rama.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
public class Revision {
    private String revisionKey;
    private String revisionEntity;
    private String mrn;
    private OffsetDateTime revisionDatetime;
    private Map<String, Object> revisionData;
    private Map<String, Object> revisionChange;
    private String createdBy;
    private String updatedBy;
}
```

### Step 4.2: Delete RevisionRepository

```bash
rm rama-spring-core/src/main/java/org/rama/repository/revision/RevisionRepository.java
```

Any references in `RevisionController` (GraphQL) need updating — but the controller queries should now route through `RevisionService` (which uses ClickHouse). If `RevisionController` directly references `RevisionRepository`, refactor it to use the service.

### Step 4.3: ClickHouseRevisionDispatcher

```java
package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.system.SystemBuffer;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ClickHouseRevisionDispatcher implements SystemBufferDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;
    private final ObjectMapper objectMapper;

    @Override
    public String bufferType() { return "revision"; }

    @Override
    public void dispatch(List<SystemBuffer> batch) {
        String safe = ClickHouseSchemaInitializer.safeIdent(tableName);
        String sql = "INSERT INTO " + safe + " ("
                + "revision_key, revision_datetime, revision_entity, mrn,"
                + " revision_data, revision_change, created_by, updated_by)"
                + " VALUES (?,?,?,?,?,?,?,?)";

        clickHouseJdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, row) -> {
            Map<String, Object> payload = parse(row.getPayload());
            ps.setString(1, str(payload.get("revisionKey")));
            ps.setTimestamp(2, ts(payload.get("revisionDatetime")));
            setNullable(ps, 3, str(payload.get("revisionEntity")));
            setNullable(ps, 4, str(payload.get("mrn")));
            ps.setString(5, jsonOf(payload.get("revisionData")));
            setNullable(ps, 6, jsonOf(payload.get("revisionChange")));
            setNullable(ps, 7, str(payload.get("createdBy")));
            setNullable(ps, 8, str(payload.get("updatedBy")));
        });
    }

    private Map<String, Object> parse(String json) {
        try { return objectMapper.readValue(json, MAP_TYPE); }
        catch (RuntimeException e) { throw new RuntimeException("Bad payload JSON: " + e.getMessage(), e); }
    }

    private String jsonOf(Object val) {
        if (val == null) return null;
        if (val instanceof String s) return s;
        try { return objectMapper.writeValueAsString(val); }
        catch (RuntimeException e) { return String.valueOf(val); }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static Timestamp ts(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t;
        if (v instanceof OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (v instanceof String s) return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        throw new IllegalArgumentException("Unsupported timestamp type: " + v.getClass());
    }
    private static void setNullable(java.sql.PreparedStatement ps, int i, String v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }
}
```

### Step 4.4: RevisionService rework

```java
@Async
@Transactional
public void saveRevision(String revisionKey, String revisionEntity,
                        Map<String, Object> revisionData,
                        Map<String, Object> revisionChange) {
    if (revisionData == null || revisionData.isEmpty()) return;

    Map<String, Object> payload = new HashMap<>();
    payload.put("revisionKey", revisionKey);
    payload.put("revisionEntity", revisionEntity);
    payload.put("revisionDatetime", OffsetDateTime.now().toString());
    payload.put("revisionData", revisionData);
    payload.put("revisionChange", revisionChange);
    if (revisionData.containsKey("mrn")) {
        payload.put("mrn", Objects.toString(revisionData.get("mrn"), null));
    }
    // createdBy/updatedBy could be populated from SecurityContext here if desired.

    String json;
    try { json = objectMapper.writeValueAsString(payload); }
    catch (RuntimeException e) {
        log.warn("Failed to serialize revision payload; skipping", e);
        return;
    }
    systemBufferService.enqueue("revision", json, "clickhouse:revision");
}

public Optional<Revision> getStateAt(String revisionKey, OffsetDateTime at) {
    if (clickHouseRepository == null) return Optional.empty();
    try {
        return clickHouseRepository.getStateAt(revisionKey, at).map(this::fromClickHouse);
    } catch (RuntimeException e) {
        log.warn("ClickHouse getStateAt failed for key={}, at={}. Cause: {}",
                revisionKey, at, e.getMessage());
        return Optional.empty();
    }
}

private Revision fromClickHouse(ClickHouseRevisionRecord r) {
    Revision rev = new Revision();
    rev.setRevisionKey(r.revisionKey());
    rev.setRevisionEntity(r.revisionEntity());
    rev.setMrn(r.mrn());
    rev.setRevisionDatetime(r.revisionDatetime());
    rev.setRevisionData(parseJsonMap(r.revisionData()));
    rev.setRevisionChange(parseJsonMap(r.revisionChange()));
    rev.setCreatedBy(r.createdBy());
    rev.setUpdatedBy(r.updatedBy());
    return rev;
}
```

Update constructor to accept `SystemBufferService`, `ObjectMapper`, and nullable `RevisionClickHouseRepository`. Drop `RevisionRepository`.

### Step 4.5: Simplify listeners

Drop the `ObjectProvider<RevisionClickHouseSink>` parameter. The listener just calls `revisionService.saveRevision(...)` — that's it. No dual-write logic. Simpler code.

### Step 4.6: Auto-config

In `RamaStarterClickHouseAutoConfiguration` (gated on `enabled=true`):
- `clickHouseDataSource` Hikari bean (initFailTimeout=-1)
- `clickHouseJdbcTemplate` bean
- `ClickHouseSchemaInitializer` bean
- `RevisionClickHouseRepository` bean
- `ClickHouseRevisionDispatcher` bean (Spring auto-discovers via `SystemBufferDispatcher` interface)

In `RamaStarterAutoConfiguration` (always when JPA enabled):
- `SystemBufferService` bean
- `SystemBufferDrainJob` bean (always enabled, but no-ops when no dispatchers are registered)

### Step 4.7: Tests

- `ClickHouseRevisionDispatcherTest` — verifies the dispatcher generates the right INSERT and binds the right values per row. Mocked JdbcTemplate.
- `RevisionServiceTest` updates — saveRevision delegates to SystemBufferService.enqueue; getStateAt queries ClickHouse, falls back to Optional.empty on error or absent CH.
- `RevisionListenerTest` updates — drop the sink test; only verify saveRevision is called.

### Step 4.8: Commit

Multiple commits per logical piece:

```bash
# Revision entity → POJO
git commit -m "refactor(revision): convert Revision to POJO; drop @Entity, RevisionRepository"

# Dispatcher
git commit -m "feat(revision): add ClickHouseRevisionDispatcher (SystemBufferDispatcher impl)"

# Service rework
git commit -m "refactor(revision): RevisionService.saveRevision enqueues to system_buffer; getStateAt reads CH only"

# Listener simplification
git commit -m "refactor(revision): listeners only call saveRevision (drop dual-write logic)"

# Auto-config
git commit -m "refactor(revision): rewire auto-config for outbox + CH dispatcher"
```

---

## Task 5: Testcontainers IT + final verification

**Files:**
- Create: `rama-spring-demo/src/test/java/org/rama/demo/clickhouse/RevisionOutboxClickHouseIntegrationIT.java`
- Modify: `CLAUDE.md`
- Create or modify: `docs/clickhouse-revision.md`

### Step 5.1: Testcontainers IT

End-to-end:
1. Spin up `clickhouse/clickhouse-server:24.8`.
2. Configure `rama.revision.clickhouse.enabled=true` + URL via `@DynamicPropertySource`.
3. Save a book (or any `@TrackRevision` entity in the demo).
4. Manually invoke the drain job (`schedulerFactoryBean.getScheduler().triggerJob(...)` or call `systemBufferDrainJob.executeInternal(mock(JobExecutionContext.class))`).
5. Query ClickHouse via `RevisionClickHouseRepository.findHistory(...)`. Assert the row is there with the expected fields.

### Step 5.2: Documentation

Update `CLAUDE.md` feature-flags section: replace any stale `rama.revision.clickhouse.*` notes.

Update or create `docs/clickhouse-revision.md`:
- Outbox architecture
- ClickHouse schema (ReplacingMergeTree, partition, sort key, skip indexes)
- Consumer setup (properties, scheduling the drain job, persistent volume on the CH server)
- Operational notes (alert on buffer size, FINAL on reads)

### Step 5.3: Final verification

```bash
mvn clean install -DskipTests -q
mvn test -Dgroups=unit
mvn -pl rama-spring-demo verify -Dgroups=integration  # includes Testcontainers IT
```

Sanity-check feature flag off:
```bash
# Without setting rama.revision.clickhouse.enabled, the starter should be byte-identical
# to current main for new consumers (no system_buffer-to-CH activity).
mvn -pl rama-spring-demo spring-boot:run  # observe logs — no ClickHouse mentions
```

### Step 5.4: Commit + push

```bash
git add rama-spring-demo/ CLAUDE.md docs/clickhouse-revision.md
git commit -m "test(revision): Testcontainers IT for outbox → drain → ClickHouse round-trip"
git push -u origin 22-clickhouse-audit-outbox
```

### Step 5.5: Create draft MR

```bash
glab mr create --draft --target-branch main \
    --title "Draft: feat(revision): ClickHouse audit log via transactional outbox (#22)" \
    --description (see below)
```

MR description: link to this plan, list the architectural shift from dual-write to outbox, the acceptance criteria from #22, and a task-completion checklist.

---

# Execution Handoff

Plan complete. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch one subagent per task with full task text + context; spec + quality review after each; merge to next.

**2. Inline** — execute in-session with checkpoint review every few steps.

For this branch the recommended approach is **subagent-driven** (5 substantial tasks, mostly independent). Use the existing subagent prompts from `superpowers:subagent-driven-development`.
