package org.rama.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Embeddable
public class TimestampField {
    @Column(updatable = false)
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
