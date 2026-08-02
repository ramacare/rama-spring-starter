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

/**
 * Configuration held per application-logged-in user -- the authenticated principal, as opposed
 * to {@link ClientUserConfig} which is keyed by the client OS user.
 */
@Entity
@Data
@NoArgsConstructor
public class UserConfig implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    private String username;

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
