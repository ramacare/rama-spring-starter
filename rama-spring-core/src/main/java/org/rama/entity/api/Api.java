package org.rama.entity.api;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

@Entity
@Data
@NoArgsConstructor
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
}
