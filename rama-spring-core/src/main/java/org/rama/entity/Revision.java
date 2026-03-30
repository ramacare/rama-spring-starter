package org.rama.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.Nationalized;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class Revision implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @NonNull
    private String revisionKey;
    private String mrn;
    private String revisionEntity;

    @NonNull
    private OffsetDateTime revisionDatetime;

    @NonNull
    @Convert(converter = JsonConverter.class)
    @Column(columnDefinition = "json")
    @Nationalized
    private Map<String, Object> revisionData;

    @Convert(converter = JsonConverter.class)
    @Column(columnDefinition = "json")
    @Nationalized
    private Map<String, Object> revisionChange;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
