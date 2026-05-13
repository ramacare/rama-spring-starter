package org.rama.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * POJO carrying one audit revision. No longer a JPA entity — revisions live in
 * ClickHouse and the outbox is a separate generic table (system_buffer).
 */
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
