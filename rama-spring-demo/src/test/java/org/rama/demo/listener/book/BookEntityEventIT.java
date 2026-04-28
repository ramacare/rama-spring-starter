package org.rama.demo.listener.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.event.book.BookCreated;
import org.rama.demo.event.book.BookUpdated;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code @EntityEvent} on a JPA entity actually causes the
 * corresponding event to be published, and that {@code @EventListener}
 * methods receive it after the create/update transaction commits.
 *
 * <p>Regression coverage for the gap where the starter shipped the
 * {@code @EntityEvent} annotation and {@code EntityEventService} bean but
 * no Hibernate listener calling {@code publishEntityEvent(...)} —
 * {@link org.rama.listener.global.GlobalPostInsertEntityEventListener}
 * and {@link org.rama.listener.global.GlobalPostUpdateEntityEventListener}
 * close that gap.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class BookEntityEventIT {

    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EventCapturingListener listener;

    // Replace the demo listener with a no-op so it does not interfere with capture order.
    @MockitoBean BookEventListener bookEventListener;

    @BeforeEach
    void resetCaptures() {
        listener.created.clear();
        listener.updated.clear();
    }

    @Test
    void create_shouldPublishBookCreatedAfterCommit() {
        Book seeded = transactionTemplate.execute(s -> {
            Book b = new Book("Created Title");
            b.setAuthor("Tester");
            return bookRepository.saveAndFlush(b);
        });

        assertThat(seeded).isNotNull();
        assertThat(listener.created)
                .hasSize(1)
                .first()
                .extracting(BookCreated::getEntity)
                .extracting(Book::getId)
                .isEqualTo(seeded.getId());
        assertThat(listener.updated).isEmpty();
    }

    @Test
    void update_shouldPublishBookUpdatedAfterCommit() {
        Book seeded = transactionTemplate.execute(s ->
                bookRepository.saveAndFlush(new Book("Update Title")));

        // Reset captures so the create from the seed step does not confuse the assertion.
        listener.created.clear();
        listener.updated.clear();

        transactionTemplate.executeWithoutResult(s -> {
            Book reloaded = bookRepository.findById(seeded.getId()).orElseThrow();
            reloaded.setAuthor("Updated Author");
            bookRepository.saveAndFlush(reloaded);
        });

        assertThat(listener.updated)
                .hasSize(1)
                .first()
                .extracting(BookUpdated::getEntity)
                .extracting(Book::getAuthor)
                .isEqualTo("Updated Author");
        assertThat(listener.created).isEmpty();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        EventCapturingListener eventCapturingListener() {
            return new EventCapturingListener();
        }
    }

    static class EventCapturingListener {
        final List<BookCreated> created = new ArrayList<>();
        final List<BookUpdated> updated = new ArrayList<>();

        @EventListener
        void onCreated(BookCreated event) {
            created.add(event);
        }

        @EventListener
        void onUpdated(BookUpdated event) {
            updated.add(event);
        }
    }
}
