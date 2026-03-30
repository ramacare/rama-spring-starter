package org.rama.entity.master;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Nationalized;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.annotation.SyncToMongo;
import org.rama.annotation.TrackRevision;
import org.rama.annotation.TransformableMap;
import org.rama.entity.*;
import org.rama.mongo.mapper.MongoMasterItemMapper;

import java.util.Map;

@Entity
@Data
@NoArgsConstructor
@TrackRevision
@SyncToMongo(mongoClass = org.rama.mongo.document.MasterItem.class, mapperClass = MongoMasterItemMapper.class)
@SyncToMeilisearch(filterableAttributes = {"groupKey", "filterText", "statusCode"})
public class MasterItem implements Auditable {
    @Id
    @Column(updatable = false, nullable = false)
    private String id;
    private String groupKey;
    private String itemCode;
    private String itemValue;
    private String itemValueAlternative;

    @ColumnDefault("0")
    private Integer ordering;

    private String keyword;
    private String filterText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "groupKey", referencedColumnName = "groupKey", insertable = false, updatable = false)
    @JsonIgnore
    private MasterGroup masterGroup;

    @Convert(converter = JsonConverter.class)
    @Column(columnDefinition = "json")
    @TransformableMap
    @Nationalized
    private Map<String, Object> properties;

    @Enumerated(EnumType.STRING)
    private StatusCode statusCode = StatusCode.active;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    public MasterItem(String groupKey, String itemCode, String itemValue) {
        this.groupKey = groupKey;
        this.itemCode = itemCode;
        this.itemValue = itemValue;
    }

    @PrePersist
    public void prePersist() {
        if (id == null && groupKey != null && itemCode != null) {
            this.id = groupKey + "^" + itemCode;
        }
    }
}
