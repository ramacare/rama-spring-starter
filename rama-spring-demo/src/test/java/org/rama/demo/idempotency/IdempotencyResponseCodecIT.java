package org.rama.demo.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.IdempotentMutation;
import org.rama.demo.entity.book.Book;
import org.rama.demo.entity.book.BookReview;
import org.rama.demo.repository.book.BookRepository;
import org.rama.demo.repository.book.BookReviewRepository;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.rama.service.idempotency.SignatureResolver;
import org.rama.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for starter#64, part 1 and 3. The dedup cache serializes the response
 * inside the mutation's transaction, and used to do so with a plain mapper:
 *
 * <ul>
 *   <li>an uninitialized lazy relation was loaded by Jackson — a reference to a missing
 *   row threw and rolled back a mutation that had succeeded;</li>
 *   <li>any other encoding failure rolled the mutation back the same way;</li>
 *   <li>the cached body was stored in plaintext, column-encrypted fields included.</li>
 * </ul>
 *
 * The clock is fixed so the no-header signature (same args within the same second)
 * is deterministic.
 */
@Tag("integration")
@SpringBootTest
@Import({IdempotencyResponseCodecIT.Mutations.class, IdempotencyResponseCodecIT.ClockOverride.class})
class IdempotencyResponseCodecIT {

    private static final Instant FIXED = Instant.parse("2026-05-15T00:00:00Z");

    @Autowired Mutations mutations;
    @Autowired SystemRequestDedupRepository dedupRepository;
    @Autowired BookRepository bookRepository;
    @Autowired BookReviewRepository bookReviewRepository;
    @Autowired SignatureResolver signatureResolver;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx.execute(s -> {
            dedupRepository.deleteAll();
            return null;
        });
        Mutations.invocations.set(0);
    }

    @Test
    void lazyReferenceToMissingRow_doesNotRollBackTheMutation() {
        String key = UUID.randomUUID().toString();

        BookReview first = mutations.reviewOfMissingBook(key);
        BookReview replay = mutations.reviewOfMissingBook(key);

        assertThat(first.getReviewer()).isEqualTo("reviewer-" + key);
        assertThat(replay.getReviewer()).isEqualTo(first.getReviewer());
        assertThat(replay.getBook()).as("an uninitialized proxy is cached as null").isNull();
        assertThat(Mutations.invocations).hasValue(1);
        assertThat(row(key).getStatus()).isEqualTo(SystemRequestDedup.Status.COMPLETED);
    }

    @Test
    void unloadedLazyRelation_isNotPulledIntoTheCache() {
        String reviewId = persistBookWithReview("Unloaded relation");

        BookReview first = mutations.loadReview(reviewId);
        BookReview replay = mutations.loadReview(reviewId);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getBook()).isNull();
        assertThat(cachedJson(reviewId)).doesNotContain("Unloaded relation");
        assertThat(Mutations.invocations).hasValue(1);
    }

    @Test
    void cachedResponse_isEncryptedAtRest() {
        String reviewId = persistBookWithReview("Encrypted title");

        mutations.loadReview(reviewId);

        String stored = row(reviewId).getResponseJson();
        assertThat(EncryptionUtil.isConfigured()).isTrue();
        assertThat(stored).doesNotContain("reviewer-");
        assertThat(cachedJson(reviewId)).contains("reviewer-");
    }

    @Test
    void unencodableResponse_keepsTheWorkAndSuppressesTheDuplicate() {
        String key = UUID.randomUUID().toString();
        long booksBefore = bookRepository.count();

        Optional<Unencodable> first = mutations.saveBookReturningUnencodable(key);
        Optional<Unencodable> replay = mutations.saveBookReturningUnencodable(key);

        assertThat(first).isPresent();
        assertThat(bookRepository.count()).as("the work committed despite the cache failure").isEqualTo(booksBefore + 1);
        assertThat(replay).as("replay carries no body, but is not a second run").isEmpty();
        assertThat(Mutations.invocations).hasValue(1);
        assertThat(row(key).getStatus()).isEqualTo(SystemRequestDedup.Status.COMPLETED);
    }

    private String persistBookWithReview(String title) {
        return tx.execute(s -> {
            Book book = new Book(title);
            book.setId(UUID.randomUUID().toString());
            bookRepository.save(book);
            BookReview review = new BookReview(book, "reviewer-" + UUID.randomUUID());
            review.setId(UUID.randomUUID().toString());
            return bookReviewRepository.save(review).getId();
        });
    }

    private SystemRequestDedup row(String arg) {
        return dedupRepository.findById(signatureResolver.resolve(new Object[]{arg})).orElseThrow();
    }

    private String cachedJson(String arg) {
        return EncryptionUtil.decrypt(row(arg).getResponseJson());
    }

    /** A response Jackson cannot write: its only property throws. */
    public static class Unencodable {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    @Component
    static class Mutations {

        static final AtomicInteger invocations = new AtomicInteger();

        @PersistenceContext EntityManager entityManager;
        @Autowired BookRepository bookRepository;
        @Autowired BookReviewRepository bookReviewRepository;

        @IdempotentMutation
        public BookReview reviewOfMissingBook(String key) {
            invocations.incrementAndGet();
            BookReview review = new BookReview(entityManager.getReference(Book.class, "missing-" + key), "reviewer-" + key);
            review.setId(key);
            return review;
        }

        @IdempotentMutation
        public BookReview loadReview(String reviewId) {
            invocations.incrementAndGet();
            return bookReviewRepository.findById(reviewId).orElseThrow();
        }

        @IdempotentMutation
        public Optional<Unencodable> saveBookReturningUnencodable(String key) {
            invocations.incrementAndGet();
            Book book = new Book("unencodable-" + key);
            book.setId(key);
            bookRepository.save(book);
            return Optional.of(new Unencodable());
        }
    }

    @TestConfiguration
    static class ClockOverride {

        @Bean(name = "idempotencyClock")
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }
}
