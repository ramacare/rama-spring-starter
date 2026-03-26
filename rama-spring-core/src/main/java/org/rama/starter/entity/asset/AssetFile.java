package org.rama.starter.entity.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.rama.starter.entity.Auditable;
import org.rama.starter.entity.TimestampField;
import org.rama.starter.entity.UserstampField;

@Entity
public class AssetFile implements Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;
    private String fileName;
    private String fileType;
    private String md5hash;
    private String originalFileName;
    private String location;
    private String bucketName;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getMd5hash() {
        return md5hash;
    }

    public void setMd5hash(String md5hash) {
        this.md5hash = md5hash;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    @Override
    public UserstampField getUserstampField() {
        return userstampField;
    }

    @Override
    public void setUserstampField(UserstampField userstampField) {
        this.userstampField = userstampField;
    }

    @Override
    public TimestampField getTimestampField() {
        return timestampField;
    }

    @Override
    public void setTimestampField(TimestampField timestampField) {
        this.timestampField = timestampField;
    }
}
