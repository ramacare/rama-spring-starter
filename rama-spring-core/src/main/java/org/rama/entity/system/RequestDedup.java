package org.rama.entity.system;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "request_dedup", indexes = {
        @Index(name = "ix_request_dedup__expires_at", columnList = "expires_at")
})
@Data
@NoArgsConstructor
public class RequestDedup {

    public enum Status { PENDING, COMPLETED }

    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(length = 255, updatable = false)
    private String method;

    @Column(length = 255, updatable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status;

    @Lob
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
