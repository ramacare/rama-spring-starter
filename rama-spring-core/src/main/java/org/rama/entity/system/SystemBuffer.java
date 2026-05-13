package org.rama.entity.system;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "system_buffer")
@Data
@NoArgsConstructor
public class SystemBuffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @Column(nullable = false, length = 100)
    private String bufferType;

    @Column(nullable = false, columnDefinition = "clob")
    private String payload;

    @Column(length = 255)
    private String target;

    @Column(nullable = false)
    private int attemptCount = 0;

    @Column(length = 2000)
    private String lastError;

    private OffsetDateTime lastAttemptAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(length = 200, updatable = false)
    private String createdBy;
}
