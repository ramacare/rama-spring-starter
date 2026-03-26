package org.rama.starter.entity.system;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.StatusCode;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

@Entity
public class SystemTemplate implements Auditable {
    @Id
    private String id;
    private String template;
    private String templateScript;

    @Enumerated(EnumType.STRING)
    private StatusCode statusCode = StatusCode.active;

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

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getTemplateScript() {
        return templateScript;
    }

    public void setTemplateScript(String templateScript) {
        this.templateScript = templateScript;
    }

    public StatusCode getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(StatusCode statusCode) {
        this.statusCode = statusCode;
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
