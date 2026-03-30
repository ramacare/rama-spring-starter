package org.rama.entity.system;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.StatusCode;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

@Entity
@Data
@NoArgsConstructor
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
}
