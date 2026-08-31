package org.rama.demo.audit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the authenticated user is stamped onto an {@code Auditable} entity
 * even when the entity is persisted on an {@code @Async} (thread-pool) thread.
 *
 * Before the fix the {@code SecurityContext} (a plain ThreadLocal) does not cross
 * the thread boundary, so {@code createdBy} comes back null/fallback instead of the
 * authenticated user.
 */
@Tag("integration")
@SpringBootTest
@Import(AsyncUserstampProbeService.class)
class AsyncUserstampPropagationIT {

    @Autowired AsyncUserstampProbeService probe;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void asyncSave_underAuthenticatedContext_stampsAuthenticatedUser() throws Exception {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        SecurityContextHolder.setContext(ctx);

        String bookId;
        try {
            bookId = probe.createBookOnAsyncThread("Async Stamped Book").get(10, TimeUnit.SECONDS);
        } finally {
            SecurityContextHolder.clearContext();
        }

        Book saved = transactionTemplate.execute(s -> bookRepository.findById(bookId).orElseThrow());
        String createdBy = saved.getUserstampField() == null ? null : saved.getUserstampField().getCreatedBy();
        assertThat(createdBy).isEqualTo("alice");
    }
}
