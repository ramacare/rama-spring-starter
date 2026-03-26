package org.rama.starter.entity.api;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

@Entity
public class Api implements Auditable {
    @Id
    private String id;
    private String sourceApiMethod;
    private String sourceApiUrl;
    private String etlCode;
    private String etlCodeError;

    @Column(name = "api_header_set_id")
    private String apiHeaderSetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_header_set_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ApiHeaderSet apiHeaderSet;

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

    public String getSourceApiMethod() {
        return sourceApiMethod;
    }

    public void setSourceApiMethod(String sourceApiMethod) {
        this.sourceApiMethod = sourceApiMethod;
    }

    public String getSourceApiUrl() {
        return sourceApiUrl;
    }

    public void setSourceApiUrl(String sourceApiUrl) {
        this.sourceApiUrl = sourceApiUrl;
    }

    public String getEtlCode() {
        return etlCode;
    }

    public void setEtlCode(String etlCode) {
        this.etlCode = etlCode;
    }

    public String getEtlCodeError() {
        return etlCodeError;
    }

    public void setEtlCodeError(String etlCodeError) {
        this.etlCodeError = etlCodeError;
    }

    public String getApiHeaderSetId() {
        return apiHeaderSetId;
    }

    public void setApiHeaderSetId(String apiHeaderSetId) {
        this.apiHeaderSetId = apiHeaderSetId;
    }

    public ApiHeaderSet getApiHeaderSet() {
        return apiHeaderSet;
    }

    public void setApiHeaderSet(ApiHeaderSet apiHeaderSet) {
        this.apiHeaderSet = apiHeaderSet;
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
