# ClickHouse Audit Log via Transactional Outbox

The rama starter writes entity revisions to ClickHouse via a generic `system_buffer` outbox.

## Architecture

```
entity tx:
   listener afterCommit → SystemBufferService.enqueue("revision", payload, "clickhouse:revision")
                            → INSERT into system_buffer (durable, transactional)

SystemBufferDrainJob (Quartz, every drain-interval):
   SELECT FROM system_buffer WHERE buffer_type = 'revision' ORDER BY id LIMIT drain-batch-size
      → ClickHouseRevisionDispatcher.dispatch(batch)
         → JdbcTemplate.batchUpdate INSERT INTO <table>
      → on success: DELETE drained rows
      → on failure: UPDATE attempt_count, last_error, last_attempt_at

Read path:
   RevisionService.getStateAt / findHistory / findByEntityAndMrn → ClickHouse FINAL query
```

## ClickHouse schema

```sql
CREATE TABLE IF NOT EXISTS revision (
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
SETTINGS index_granularity = 8192;
```

`ReplacingMergeTree(ingested_at)` deduplicates rows with the same `(revision_key, revision_datetime)`. The schema initializer runs `CREATE TABLE IF NOT EXISTS` at boot; existing tables are left untouched. Reads use `FINAL` to surface the latest version eagerly.

## Consumer setup

### Properties

```properties
rama.revision.clickhouse.enabled=true
rama.revision.clickhouse.url=jdbc:clickhouse://clickhouse-host:8123/default
rama.revision.clickhouse.username=default
rama.revision.clickhouse.password=
rama.revision.clickhouse.table-name=revision
rama.revision.clickhouse.drain-batch-size=1000
rama.revision.clickhouse.drain-interval=30s
```

### Liquibase

The starter migration adds the `system_buffer` table automatically via `db/changelog/rama-spring-system.changelog.yaml` (`rama-spring-system-008-system-buffer`).

The SQL `revision` table from previous starter versions is no longer created. Existing consumers keep their tables (Liquibase doesn't drop them); the starter just stops touching them.

### Quartz drain job

`SystemBufferDrainJob` is scheduled via a `JobDetail` + `SimpleTrigger` registered in `RamaStarterClickHouseAutoConfiguration` when `rama.revision.clickhouse.enabled=true`. Spring Boot's Quartz auto-config picks these up and runs the job on the configured interval. The job is durable, requests recovery, and uses Spring's `SpringBeanJobFactory` so its dispatcher dependencies are injected per execution.

### Persistent volume

In production, mount a persistent volume to `/var/lib/clickhouse/` on the ClickHouse server. The ZSTD codec keeps revision history compact, but volume sizing should still account for write growth.

## Operational notes

- **Alert on `system_buffer` size.** Healthy steady state is < a few hundred rows. Sustained growth means the dispatcher is failing — check `last_error` on the rows.
- **Reads use FINAL.** This is more expensive than a regular query but eagerly merges replacements. For analytical workloads, query without FINAL and accept stale-by-merge data.
- **Backfill from existing SQL revision tables** (legacy consumers): write a one-shot Quartz job that pages the SQL revision table and inserts into ClickHouse with the existing `revision_datetime`. The plan calls this `RevisionLegacyMigrationJob` (not yet implemented).
- **`drain-interval` trades latency for buffer size.** 30s is the default. Shorter intervals reduce buffer accumulation; longer intervals reduce ClickHouse INSERT pressure.
