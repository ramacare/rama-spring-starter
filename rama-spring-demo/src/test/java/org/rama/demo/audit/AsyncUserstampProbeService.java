package org.rama.demo.audit;

import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

/**
 * Test probe: persists an {@link org.rama.entity.Auditable} entity from inside an
 * {@code @Async} method, so the Hibernate flush (and thus the global userstamp
 * listener) runs on the async pool thread rather than the calling thread.
 *
 * Registered via {@code @Import}, not {@code @Component}, so it stays out of the
 * demo component scan and only loads in the test that imports it.
 */
public class AsyncUserstampProbeService {

    private final BookRepository bookRepository;

    public AsyncUserstampProbeService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Async
    @Transactional
    public CompletableFuture<String> createBookOnAsyncThread(String title) {
        Book book = new Book(title);
        bookRepository.saveAndFlush(book);
        return CompletableFuture.completedFuture(book.getId());
    }
}
