package org.rama.entity.asset;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.rama.entity.Auditable;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

@Entity
@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class AssetFile implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @NonNull
    private String fileName;
    @NonNull
    private String fileType;
    @NonNull
    private String md5hash;
    private String originalFileName;
    @NonNull
    private String location;
    @NonNull
    private String bucketName;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();
}
