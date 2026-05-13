package org.rama.clickhouse;

import java.time.OffsetDateTime;

/**
 * Wire shape for one revision row going to ClickHouse. No surrogate id —
 * natural identity is (revisionKey, revisionDatetime). ReplacingMergeTree
 * on ClickHouse uses ingested_at as the dedup version; the dispatcher
 * doesn't set it (relies on the ClickHouse column default now64(3)).
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
