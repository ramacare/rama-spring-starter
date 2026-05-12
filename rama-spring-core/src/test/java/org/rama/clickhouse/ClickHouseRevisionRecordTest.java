package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

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
