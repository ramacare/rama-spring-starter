package org.rama.entity.system;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.JsonConverter;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.util.Map;

@Entity
@Data
@NoArgsConstructor
public class SystemLog implements Auditable {
    public enum LogLevel {
        INFO, WARN, ERROR, DEBUG
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    private LogLevel logLevel;
    private String logKey;
    private String message;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> detail;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
