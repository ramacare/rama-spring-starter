package org.rama.starter.entity.system;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.JsonConverter;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

import java.util.Map;

@Entity
public class SystemLog implements Auditable {
    public enum LogLevel {
        INFO, WARN, ERROR, DEBUG
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    private LogLevel logLevel;
    private String logKey;
    private String message;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> detail;

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

    public LogLevel getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(LogLevel logLevel) {
        this.logLevel = logLevel;
    }

    public String getLogKey() {
        return logKey;
    }

    public void setLogKey(String logKey) {
        this.logKey = logKey;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
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
