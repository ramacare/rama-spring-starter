package org.rama.meilisearch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the race MeilisearchService.sync() hit under real concurrency (multiple
 * {@code @Async} threads synced items for the same not-yet-seen split value at once): a losing
 * thread must BLOCK until the winner's initializer actually finishes, not merely see the index
 * "claimed" and proceed to write a document (or let a search run) against a not-yet-configured
 * index.
 */
class EnsuredMeilisearchIndexesTest {

    @Test
    void ensureInitialized_runsTheInitializerExactlyOnce_acrossManyConcurrentCallers() throws InterruptedException {
        EnsuredMeilisearchIndexes ensured = new EnsuredMeilisearchIndexes();
        AtomicInteger initializerRuns = new AtomicInteger();
        AtomicInteger winners = new AtomicInteger();
        int callers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);

        List<Boolean> results = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    boolean initializedByMe = ensured.ensureInitialized("shared-index", initializerRuns::incrementAndGet);
                    results.add(initializedByMe);
                    if (initializedByMe) {
                        winners.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(initializerRuns.get()).as("initializer must run exactly once no matter how many callers race it").isEqualTo(1);
        assertThat(winners.get()).isEqualTo(1);
        assertThat(results).hasSize(callers);
    }

    @Test
    void ensureInitialized_blocksLosingCallers_untilTheWinnersInitializerFullyCompletes() throws InterruptedException {
        EnsuredMeilisearchIndexes ensured = new EnsuredMeilisearchIndexes();
        CountDownLatch winnerStarted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicBoolean winnerFinished = new AtomicBoolean(false);
        AtomicBoolean loserSawIncompleteWork = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> ensured.ensureInitialized("racy-index", () -> {
                winnerStarted.countDown();
                await(releaseWinner);
                winnerFinished.set(true);
            }));

            assertThat(winnerStarted.await(2, TimeUnit.SECONDS)).as("winner should have started").isTrue();

            var loserDone = pool.submit(() -> {
                boolean initializedByMe = ensured.ensureInitialized("racy-index", () -> {
                    throw new AssertionError("loser must never run the initializer itself");
                });
                // The critical assertion: by the time this call RETURNS, the winner's work must
                // already be done -- this is exactly the bug (a loser proceeding to write a
                // document, or serve a search, before settings were actually applied).
                if (!winnerFinished.get()) {
                    loserSawIncompleteWork.set(true);
                }
                return initializedByMe;
            });

            // Give the loser a moment to actually reach ensureInitialized and block -- then prove
            // it really is blocked (hasn't finished) before releasing the winner.
            Thread.sleep(200);
            assertThat(loserDone.isDone()).as("loser must still be blocked while the winner is mid-initialization").isFalse();

            releaseWinner.countDown();
            Boolean loserInitializedByMe = loserDone.get(5, TimeUnit.SECONDS);

            assertThat(loserInitializedByMe).isFalse();
            assertThat(loserSawIncompleteWork.get())
                    .as("loser must never observe incomplete winner work after ensureInitialized returns")
                    .isFalse();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ensureInitialized_propagatesTheWinnersFailure_toEveryBlockedLoser() throws InterruptedException {
        EnsuredMeilisearchIndexes ensured = new EnsuredMeilisearchIndexes();
        RuntimeException boom = new RuntimeException("meilisearch unreachable");
        CountDownLatch winnerFailed = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            pool.submit(() -> {
                try {
                    ensured.ensureInitialized("failing-index", () -> { throw boom; });
                } catch (RuntimeException ignored) {
                    // expected -- the winner rethrows its own exception synchronously
                } finally {
                    winnerFailed.countDown();
                }
            });
            assertThat(winnerFailed.await(2, TimeUnit.SECONDS)).isTrue();

            // A caller arriving after the winner already failed must still see the failure, not
            // silently proceed as though the index were configured.
            assertThatThrownBy(() -> ensured.ensureInitialized("failing-index", () -> { throw new AssertionError("must not re-run"); }))
                    .isInstanceOf(CompletionException.class)
                    .hasCause(boom);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ensureInitialized_usesIndependentClaims_perIndexName() {
        EnsuredMeilisearchIndexes ensured = new EnsuredMeilisearchIndexes();
        AtomicInteger runsForA = new AtomicInteger();
        AtomicInteger runsForB = new AtomicInteger();

        IntStream.range(0, 3).forEach(i -> ensured.ensureInitialized("index-a", runsForA::incrementAndGet));
        IntStream.range(0, 3).forEach(i -> ensured.ensureInitialized("index-b", runsForB::incrementAndGet));

        assertThat(runsForA.get()).isEqualTo(1);
        assertThat(runsForB.get()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
