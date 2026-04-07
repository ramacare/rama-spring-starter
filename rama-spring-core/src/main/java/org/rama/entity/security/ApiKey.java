package org.rama.entity.security;

import jakarta.persistence.*;
import lombok.*;
import org.rama.entity.Auditable;
import org.rama.entity.JsonConverter;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class ApiKey implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @NonNull
    private String keyHash;
    @NonNull
    private String username;

    @Convert(converter = JsonConverter.class)
    @Column(columnDefinition = "json")
    private List<String> roles;

    private boolean enabled;

    private OffsetDateTime expiresAt;

    private OffsetDateTime lastUsedAt;

    @Embedded
    private UserstampField userstampField = new UserstampField();
    @Embedded
    private TimestampField timestampField = new TimestampField();
}
