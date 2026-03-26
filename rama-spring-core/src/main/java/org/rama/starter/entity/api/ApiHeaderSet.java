package org.rama.starter.entity.api;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.JsonEncryptConverter;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
public class ApiHeaderSet implements Auditable {
    @Id
    @Column(updatable = false, nullable = false)
    private String id;

    @Convert(converter = JsonEncryptConverter.class)
    @Column(length = 4000)
    private List<Map<String, String>> headers = new ArrayList<>();

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<Map<String, String>> getHeaders() {
        return headers;
    }

    public void setHeaders(List<Map<String, String>> headers) {
        this.headers = headers;
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
