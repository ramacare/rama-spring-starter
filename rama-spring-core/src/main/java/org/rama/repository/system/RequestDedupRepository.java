package org.rama.repository.system;

import jakarta.persistence.LockModeType;
import org.rama.entity.system.RequestDedup;
import org.rama.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RequestDedupRepository extends BaseRepository<RequestDedup, String> {

    /**
     * Locking SELECT for the dedup row. Used by the aspect to serialise concurrent
     * callers on the same signature: the first thread holds the row-lock while it
     * runs the underlying work and writes the response back; later threads block
     * here until that commit lands, then read the cached response.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequestDedup r where r.id = :id")
    Optional<RequestDedup> findBySignatureForUpdate(@Param("id") String id);

    @Modifying
    @Query("delete from RequestDedup r where r.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
