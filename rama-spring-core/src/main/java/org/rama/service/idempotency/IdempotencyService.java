package org.rama.service.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.system.RequestDedup;
import org.rama.entity.system.RequestDedup.Status;
import org.rama.repository.system.RequestDedupRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private final RequestDedupRepository repository;
    private final EntityManager entityManager;
    private final ResponseCodec responseCodec;

    /**
     * Atomic-or-noop claim of the dedup slot. Returns {@code true} if this
     * caller inserted a fresh PENDING row, {@code false} if a row already
     * exists for the signature.
     *
     * REQUIRES_NEW so a unique-constraint rollback never poisons the caller's
     * transaction. Dialect-agnostic — relies on the PK constraint to reject
     * duplicates, not on database-specific UPSERT syntax.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(String signature, String method, String username,
                            OffsetDateTime now, OffsetDateTime expiresAt) {
        RequestDedup row = new RequestDedup();
        row.setId(signature);
        row.setMethod(truncate(method, 255));
        row.setUsername(truncate(username, 255));
        row.setStatus(Status.PENDING);
        row.setCreatedAt(now);
        row.setExpiresAt(expiresAt);
        try {
            entityManager.persist(row);
            entityManager.flush();
            return true;
        } catch (PersistenceException | DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Holds the dedup row's pessimistic write-lock for the duration of one
     * REQUIRES_NEW transaction:
     *
     *   - COMPLETED-within-TTL → deserialise and return the cached response;
     *     the work {@code Supplier} is never invoked.
     *   - PENDING or expired   → invoke the work, persist its response, mark
     *     COMPLETED, commit. The lock release on commit happens after the
     *     write is durable, so concurrent callers either see COMPLETED or
     *     block until we commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public Object lockAndExecute(String signature, Type returnType,
                                 OffsetDateTime now, OffsetDateTime expiresAt,
                                 ThrowingSupplier<Object> work) throws Throwable {
        RequestDedup row = repository.findBySignatureForUpdate(signature)
                .orElseThrow(() -> new IllegalStateException("Dedup row vanished after claim: " + signature));

        if (row.getStatus() == Status.COMPLETED && row.getExpiresAt().isAfter(now)) {
            log.debug("idempotency cache hit signature={} method={}", signature, row.getMethod());
            return responseCodec.decode(row.getResponseJson(), returnType);
        }

        Object result = work.get();
        row.setStatus(Status.COMPLETED);
        row.setResponseJson(responseCodec.encode(result));
        row.setExpiresAt(expiresAt);
        repository.save(row);
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
