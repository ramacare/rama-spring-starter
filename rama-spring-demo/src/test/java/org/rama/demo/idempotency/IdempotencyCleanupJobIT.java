package org.rama.demo.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.rama.entity.system.RequestDedup;
import org.rama.entity.system.RequestDedup.Status;
import org.rama.job.system.RequestDedupCleanupJob;
import org.rama.repository.system.RequestDedupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class IdempotencyCleanupJobIT {

    @Autowired RequestDedupCleanupJob job;
    @Autowired RequestDedupRepository repository;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx.execute(s -> {
            repository.deleteAll();
            return null;
        });
    }

    @Test
    void deletesExpiredRows_keepsFutureRows() {
        OffsetDateTime now = OffsetDateTime.now();

        tx.execute(s -> {
            repository.save(row("expired-1", now.minusMinutes(10)));
            repository.save(row("expired-2", now.minusSeconds(1)));
            repository.save(row("fresh-1", now.plusMinutes(5)));
            repository.save(row("fresh-2", now.plusMinutes(30)));
            return null;
        });
        assertThat(repository.count()).isEqualTo(4L);

        job.executeInternal(new JobDataMap());

        assertThat(repository.count()).isEqualTo(2L);
        assertThat(repository.existsById("expired-1")).isFalse();
        assertThat(repository.existsById("expired-2")).isFalse();
        assertThat(repository.existsById("fresh-1")).isTrue();
        assertThat(repository.existsById("fresh-2")).isTrue();
    }

    @Test
    void emptyTable_isNoOp() {
        assertThat(repository.count()).isZero();
        job.executeInternal(new JobDataMap());
        assertThat(repository.count()).isZero();
    }

    private static RequestDedup row(String id, OffsetDateTime expiresAt) {
        RequestDedup r = new RequestDedup();
        r.setId(id);
        r.setMethod("test-method");
        r.setUsername("test-user");
        r.setStatus(Status.COMPLETED);
        r.setResponseJson("\"x\"");
        r.setCreatedAt(expiresAt.minusMinutes(1));
        r.setExpiresAt(expiresAt);
        return r;
    }
}
