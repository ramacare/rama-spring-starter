package org.rama.starter.entity.system;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.JsonConverter;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
public class ClientConfig implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;
    private String computerName;
    private String fingerprint;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> configuration = new HashMap<>();

    private OffsetDateTime lastSeenDatetime;

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

    public String getComputerName() {
        return computerName;
    }

    public void setComputerName(String computerName) {
        this.computerName = computerName;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }

    public OffsetDateTime getLastSeenDatetime() {
        return lastSeenDatetime;
    }

    public void setLastSeenDatetime(OffsetDateTime lastSeenDatetime) {
        this.lastSeenDatetime = lastSeenDatetime;
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
