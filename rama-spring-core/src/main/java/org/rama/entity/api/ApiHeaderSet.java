package org.rama.entity.api;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.JsonEncryptConverter;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Data
@NoArgsConstructor
public class ApiHeaderSet implements Auditable {
    @Id
    @Column(updatable = false, nullable = false)
    private String id;

    @Convert(converter = JsonEncryptConverter.class)
    @Column(length = 4000)
    private List<Map<String, String>> headers = new ArrayList<>();

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
