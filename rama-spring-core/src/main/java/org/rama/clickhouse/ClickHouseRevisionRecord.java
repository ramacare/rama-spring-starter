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
