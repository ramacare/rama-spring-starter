package org.rama.starter.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.JsonConverter;
import org.rama.starter.entity.StatusCode;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

import java.util.List;
import java.util.Map;

@Entity
public class MasterGroup implements Auditable {
    @Id
    private String groupKey;
    private String groupName;
    private String description;
    private String propertiesTemplate;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> defaultProperties;

    @OneToMany(mappedBy = "masterGroup")
    private List<MasterItem> masterItems;

    @Enumerated(EnumType.STRING)
    private StatusCode statusCode = StatusCode.active;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPropertiesTemplate() {
        return propertiesTemplate;
    }

    public void setPropertiesTemplate(String propertiesTemplate) {
        this.propertiesTemplate = propertiesTemplate;
    }

    public Map<String, Object> getDefaultProperties() {
        return defaultProperties;
    }

    public void setDefaultProperties(Map<String, Object> defaultProperties) {
        this.defaultProperties = defaultProperties;
    }

    public List<MasterItem> getMasterItems() {
        return masterItems;
    }

    public void setMasterItems(List<MasterItem> masterItems) {
        this.masterItems = masterItems;
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
