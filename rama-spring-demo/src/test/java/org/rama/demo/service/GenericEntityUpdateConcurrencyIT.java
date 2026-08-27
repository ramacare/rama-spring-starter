package org.rama.demo.service;

import graphql.GraphQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.rama.service.GenericEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for starter#41. {@code updateEntity} implements a hand-rolled
 * optimistic-concurrency check — the client echoes back the {@code updatedAt} it saw,
 * and the update is rejected if the row has moved on since. Read without a lock, two
 * overlapping updaters both observe the same {@code updatedAt}, both pass the check,
 * and one write is silently lost.
 *
 * <p>Both threads here submit the <em>same</em> stale timestamp. Before the fix both
 * were accepted; with the read taken under {@code PESSIMISTIC_WRITE} the second caller
 * blocks until the first commits, then observes the advanced timestamp and is rejected.
 *
 * <p>This cannot be reproduced with Mockito — it needs a real transaction manager and
 * two real transactions.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class GenericEntityUpdateConcurrencyIT {

    @Autowired GenericEntityService genericEntityService;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void twoUpdatersWithTheSameStaleTimestamp_exactlyOneSucceeds() throws Exception {
        String id = UUID.randomUUID().toString();

        tx.execute(s -> {
            Book book = new Book("Concurrency In Practice");
            book.setId(id);
            book.setAuthor("Goetz");
            return bookRepository.saveAndFlush(book);
        });

        // Re-read so the timestamp is the database's own value: OffsetDateTime.now()
        // carries more precision than the column keeps, and comparing an in-memory
        // value against a stored one would fail for reasons unrelated to concurrency.
        OffsetDateTime stale = tx.execute(s ->
                bookRepository.findById(id).orElseThrow().getTimestampField().getUpdatedAt());
        assertThat(stale).as("insert stamps updatedAt, so there is something to conflict on").isNotNull();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Throwable> conflict = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            String title = "Rewritten by thread " + i;
            pool.submit(() -> {
                try {
                    // Each thread needs its own map: updateEntity mutates the input.
                    Map<String, Object> input = new HashMap<>();
                    input.put("title", title);
                    input.put("timestampField", new HashMap<>(Map.of("updatedAt", stale.toString())));

                    ready.countDown();
                    fire.await(5, TimeUnit.SECONDS);
                    genericEntityService.updateEntity(Book.class, bookRepository, id, input);
                    succeeded.incrementAndGet();
                } catch (GraphQLException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Conflict detected")) {
                        conflict.set(e);
                    } else {
                        unexpected.set(e);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        fire.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("both threads finished").isTrue();

        assertThat(unexpected.get()).as("neither thread failed for an unrelated reason").isNull();
        assertThat(succeeded.get())
                .as("exactly one update is accepted — before starter#41 both were, and one write was lost")
                .isEqualTo(1);
        assertThat(conflict.get())
                .as("the loser is rejected by the updatedAt check, not by a lock timeout")
                .isNotNull();

        Book after = tx.execute(s -> bookRepository.findById(id).orElseThrow());
        assertThat(after.getTimestampField().getUpdatedAt())
                .as("the winner's write advanced updatedAt")
                .isAfter(stale);
    }
}
