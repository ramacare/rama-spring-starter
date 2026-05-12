# ClickHouse-Backed Revision Audit Log

## Overview

When `rama.revision.clickhouse.enabled=true`, the starter dual-writes every
revision to ClickHouse alongside the SQL `revision` table, and serves
point-in-time / history queries from ClickHouse via `RevisionService`.

The SQL `revision` table remains the synchronous source of truth. ClickHouse
is an async, batched mirror — if a batch insert fails, the SQL row is still
durable and the `RevisionClickHouseBackfillJob` can re-synchronize.

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
  When ClickHouse comes back, `RevisionClickHouseBackfillJob` repopulates
  missing rows from the SQL `revision` table on its next scheduled run
  (consumers schedule it via `QuartzService.scheduleJob` — e.g. nightly).
