package org.rama.repository.system;

import jakarta.persistence.LockModeType;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface SystemRequestDedupRepository extends BaseRepository<SystemRequestDedup, String> {

    /**
     * Locking SELECT for the dedup row. Used by the aspect to serialise concurrent
     * callers on the same signature: the first thread holds the row-lock while it
     * runs the underlying work and writes the response back; later threads block
     * here until that commit lands, then read the cached response.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SystemRequestDedup r where r.id = :id")
    Optional<SystemRequestDedup> findBySignatureForUpdate(@Param("id") String id);

    /**
     * {@code @Transactional} here rather than on the caller. Spring Data gives a declared
     * query method no transaction attribute of its own and {@code @Modifying} does not
     * imply one, so Hibernate 7.2 rejects this with
     * {@code TransactionRequiredException: No active transaction for update or delete query}
     * (Hibernate 6 auto-committed it silently).
     *
     * <p>The caller cannot supply it: Quartz enters through {@code SmartJob.execute}, which
     * reaches {@code executeInternal} by self-invocation, so {@code @Transactional} on the
     * job never applies. This is the one placement self-invocation cannot defeat, because
     * the repository proxy is a different object. See starter#47.
     */
    @Modifying
    @Transactional
    @Query("delete from SystemRequestDedup r where r.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
