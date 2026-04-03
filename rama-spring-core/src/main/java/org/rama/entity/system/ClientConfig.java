package org.rama.entity.system;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Nationalized;
import org.rama.entity.Auditable;
import org.rama.entity.JsonConverter;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Data
@NoArgsConstructor
public class ClientConfig implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;
    private String computerName;
    private String fingerprint;

    @Nationalized
    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> configuration = new HashMap<>();

    private OffsetDateTime lastSeenDatetime;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
