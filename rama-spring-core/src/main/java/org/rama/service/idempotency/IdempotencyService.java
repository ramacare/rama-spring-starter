package org.rama.service.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.system.RequestDedup;
import org.rama.entity.system.RequestDedup.Status;
import org.rama.repository.system.RequestDedupRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Type;
import java.time.OffsetDateTime;

@Slf4j
public class IdempotencyService {

    private final RequestDedupRepository repository;
    private final EntityManager entityManager;
    private final ResponseCodec responseCodec;
    private final TransactionTemplate claimTransactionTemplate;
    private final TransactionTemplate lockTransactionTemplate;

    public IdempotencyService(RequestDedupRepository repository,
                              EntityManager entityManager,
                              ResponseCodec responseCodec,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.responseCodec = responseCodec;
        this.claimTransactionTemplate = new TransactionTemplate(transactionManager);
        this.claimTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lockTransactionTemplate = new TransactionTemplate(transactionManager);
        this.lockTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Atomic-or-noop claim of the dedup slot. Returns {@code true} if this
     * caller inserted a fresh PENDING row, {@code false} if a row already
     * exists for the signature.
     *
     * Uses programmatic transaction control (TransactionTemplate set to
     * REQUIRES_NEW) so we can call {@code setRollbackOnly()} explicitly on
     * the duplicate-key path. With a {@code @Transactional} proxy, catching
     * the constraint violation would still leave the transaction marked
     * rollback-only by Hibernate and Spring would throw
     * {@code UnexpectedRollbackException} at commit time.
     *
     * Dialect-agnostic: relies on the PK constraint to reject duplicates,
     * not on database-specific UPSERT syntax.
     */
    public boolean tryClaim(String signature, String method, String username,
                            OffsetDateTime now, OffsetDateTime expiresAt) {
        Boolean result = claimTransactionTemplate.execute(status -> {
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
                return Boolean.TRUE;
            } catch (PersistenceException | DataIntegrityViolationException e) {
                status.setRollbackOnly();
                return Boolean.FALSE;
            }
        });
        return Boolean.TRUE.equals(result);
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
     *
     * Uses programmatic transaction control for the same reason as
     * {@link #tryClaim} — and to avoid any class-level proxy interaction
     * that would otherwise propagate the inner persist's rollback-only
     * mark onto the outer call.
     */
    public Object lockAndExecute(String signature, Type returnType,
                                 OffsetDateTime now, OffsetDateTime expiresAt,
                                 ThrowingSupplier<Object> work) throws Throwable {
        Throwable[] thrown = new Throwable[1];
        try {
            return lockTransactionTemplate.execute(status -> {
                RequestDedup row = repository.findBySignatureForUpdate(signature)
                        .orElseThrow(() -> new IllegalStateException("Dedup row vanished after claim: " + signature));

                if (row.getStatus() == Status.COMPLETED && row.getExpiresAt().isAfter(now)) {
                    log.debug("idempotency cache hit signature={} method={}", signature, row.getMethod());
                    return responseCodec.decode(row.getResponseJson(), returnType);
                }

                Object result;
                try {
                    result = work.get();
                } catch (Throwable t) {
                    thrown[0] = t;
                    status.setRollbackOnly();
                    if (t instanceof RuntimeException re) throw re;
                    if (t instanceof Error err) throw err;
                    throw new RuntimeException(t);
                }
                row.setStatus(Status.COMPLETED);
                row.setResponseJson(responseCodec.encode(result));
                row.setExpiresAt(expiresAt);
                repository.save(row);
                return result;
            });
        } catch (Throwable outer) {
            if (thrown[0] != null) throw thrown[0];
            throw outer;
        }
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
