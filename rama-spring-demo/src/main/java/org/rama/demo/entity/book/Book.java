package org.rama.demo.entity.book;

import jakarta.persistence.*;
import lombok.*;
import org.rama.annotation.EntityEvent;
import org.rama.demo.event.book.BookCreated;
import org.rama.demo.event.book.BookUpdated;
import org.rama.entity.Auditable;
import org.rama.entity.StatusCode;
import org.rama.entity.TimestampField;
import org.rama.entity.UserstampField;

import java.util.UUID;

@Entity
@Table(name = "book")
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@EntityEvent(createdEvent = BookCreated.class, updatedEvent = BookUpdated.class)
public class Book implements Auditable {

    @Id
    @Column(updatable = false, nullable = false, length = 36)
    private String id;

    @NonNull
    @Column(nullable = false)
    private String title;

    private String author;

    @Column(unique = true)
    private String isbn;

    private Integer publishedYear;

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
