package org.rama.starter.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.Nationalized;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRevisionKey() {
        return revisionKey;
    }

    public void setRevisionKey(String revisionKey) {
        this.revisionKey = revisionKey;
    }

    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public String getRevisionEntity() {
        return revisionEntity;
    }

    public void setRevisionEntity(String revisionEntity) {
        this.revisionEntity = revisionEntity;
    }

    public OffsetDateTime getRevisionDatetime() {
        return revisionDatetime;
    }

    public void setRevisionDatetime(OffsetDateTime revisionDatetime) {
        this.revisionDatetime = revisionDatetime;
    }

    public Map<String, Object> getRevisionData() {
        return revisionData;
    }

    public void setRevisionData(Map<String, Object> revisionData) {
        this.revisionData = revisionData;
    }

    public Map<String, Object> getRevisionChange() {
        return revisionChange;
    }

    public void setRevisionChange(Map<String, Object> revisionChange) {
        this.revisionChange = revisionChange;
    }

    @Override
    public UserstampField getUserstampField() {
        return userstampField;
    }

    @Override
    public void setUserstampField(UserstampField userstampField) {
        this.userstampField = userstampField;
    }

    @Override
    public TimestampField getTimestampField() {
        return timestampField;
    }

    @Override
    public void setTimestampField(TimestampField timestampField) {
        this.timestampField = timestampField;
    }
}
