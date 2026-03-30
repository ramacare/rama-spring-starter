package org.rama.entity.system;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

@Entity
@Data
@NoArgsConstructor
public class SystemParameter implements Auditable {
    @Id
    private String parameterKey;
    private String parameterValue;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
