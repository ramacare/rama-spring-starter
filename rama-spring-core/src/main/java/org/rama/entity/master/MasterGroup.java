package org.rama.entity.master;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rama.entity.*;

import java.util.List;
import java.util.Map;

@Entity
@Data
@NoArgsConstructor
public class MasterGroup implements Auditable {
    @Id
    private String groupKey;
    private String groupName;
    private String description;
    private String propertiesTemplate;

    @Convert(converter = JsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> defaultProperties;

    @OneToMany(mappedBy = "masterGroup")
    private List<MasterItem> masterItems;

    @Enumerated(EnumType.STRING)
    private StatusCode statusCode = StatusCode.active;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
