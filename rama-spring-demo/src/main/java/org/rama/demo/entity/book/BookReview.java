package org.rama.demo.entity.book;

import jakarta.persistence.*;
import lombok.*;
import org.rama.annotation.TrackRevision;
import org.rama.entity.Auditable;
import org.rama.entity.StatusCode;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.util.UUID;

@Entity
@Table(name = "book_review")
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@TrackRevision
public class BookReview implements Auditable {

    @Id
    @Column(updatable = false, nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    @NonNull
    private Book book;

    @NonNull
    private String reviewer;

    private Integer rating;

    @Lob
    @Column(columnDefinition = "clob")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_code", nullable = false)
    private StatusCode statusCode = StatusCode.active;

    @Embedded
    private UserstampField userstampField = new UserstampField();

    @Embedded
    private TimestampField timestampField = new TimestampField();

    @PrePersist
    void ensureId() {
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
    }
}
