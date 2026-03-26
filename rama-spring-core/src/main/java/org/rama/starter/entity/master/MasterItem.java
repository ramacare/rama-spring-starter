package org.rama.starter.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.JsonConverter;
import org.rama.starter.entity.StatusCode;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

import java.util.Map;

@Entity
public class MasterItem implements Auditable {
    @Id
    @Column(updatable = false, nullable = false)
    private String id;
    private String groupKey;
    private String itemCode;
    private String itemValue;
    private String itemValueAlternative;
    private Integer ordering;
    private String keyword;
    private String filterText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "groupKey", referencedColumnName = "groupKey", insertable = false, updatable = false)
    private MasterGroup masterGroup;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> properties;

    @Enumerated(EnumType.STRING)
    private StatusCode statusCode = StatusCode.active;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    @PrePersist
    public void prePersist() {
        if (id == null && groupKey != null && itemCode != null) {
            this.id = groupKey + "^" + itemCode;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public String getItemValue() {
        return itemValue;
    }

    public void setItemValue(String itemValue) {
        this.itemValue = itemValue;
    }

    public String getItemValueAlternative() {
        return itemValueAlternative;
    }

    public void setItemValueAlternative(String itemValueAlternative) {
        this.itemValueAlternative = itemValueAlternative;
    }

    public Integer getOrdering() {
        return ordering;
    }

    public void setOrdering(Integer ordering) {
        this.ordering = ordering;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getFilterText() {
        return filterText;
    }

    public void setFilterText(String filterText) {
        this.filterText = filterText;
    }

    public MasterGroup getMasterGroup() {
        return masterGroup;
    }

    public void setMasterGroup(MasterGroup masterGroup) {
        this.masterGroup = masterGroup;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
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
