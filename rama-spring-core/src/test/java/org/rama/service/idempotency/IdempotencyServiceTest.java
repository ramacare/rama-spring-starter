package org.rama.service.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.SystemRequestDedup;
import org.rama.entity.system.SystemRequestDedup.Status;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock SystemRequestDedupRepository repository;
    @Mock EntityManager entityManager;
    @Mock PlatformTransactionManager transactionManager;
    ResponseCodec responseCodec = new ResponseCodec();

    IdempotencyService service;

    @BeforeEach
    void wire() {
        service = new IdempotencyService(repository, entityManager, responseCodec, transactionManager);
    }

    @Test
    void tryClaim_persistsAndReturnsTrueOnFreshSignature() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");

        boolean claimed = service.tryClaim("sig-1", "method-x", "alice", now, now.plusSeconds(30));

        assertThat(claimed).isTrue();
        verify(entityManager).persist(any(SystemRequestDedup.class));
        verify(entityManager).flush();
    }

    @Test
    void tryClaim_returnsFalseWhenPersistenceExceptionOnFlush() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        willThrow(new PersistenceException("dup key")).given(entityManager).flush();

        boolean claimed = service.tryClaim("sig-dup", "method-y", "bob",
                OffsetDateTime.now(), OffsetDateTime.now().plusSeconds(30));

        assertThat(claimed).isFalse();
    }

    @Test
    void lockAndExecute_returnsCachedResponse_whenCompletedAndFresh() throws Throwable {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");

        SystemRequestDedup row = new SystemRequestDedup();
        row.setId("sig-cached");
        row.setStatus(Status.COMPLETED);
        row.setExpiresAt(now.plusSeconds(30));
        row.setResponseJson("\"cached-value\"");

        given(repository.findBySignatureForUpdate("sig-cached")).willReturn(Optional.of(row));

        AtomicInteger workInvocations = new AtomicInteger();
        Object result = service.lockAndExecute("sig-cached", String.class, now, now.plusSeconds(30), () -> {
            workInvocations.incrementAndGet();
            return "fresh-value";
        });

        assertThat(result).isEqualTo("cached-value");
        assertThat(workInvocations.get()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void lockAndExecute_runsWorkAndPersistsResponse_whenPending() throws Throwable {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");

        SystemRequestDedup row = new SystemRequestDedup();
        row.setId("sig-pending");
        row.setStatus(Status.PENDING);
        row.setExpiresAt(now.plusSeconds(30));
        row.setResponseJson(null);

        given(repository.findBySignatureForUpdate("sig-pending")).willReturn(Optional.of(row));

        Object result = service.lockAndExecute("sig-pending", String.class, now, now.plusSeconds(30),
                () -> "newly-computed");

        assertThat(result).isEqualTo("newly-computed");

        ArgumentCaptor<SystemRequestDedup> captor = ArgumentCaptor.forClass(SystemRequestDedup.class);
        verify(repository).save(captor.capture());
        SystemRequestDedup saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(saved.getResponseJson()).isEqualTo("\"newly-computed\"");
    }

    @Test
    void lockAndExecute_runsWorkAndPersistsResponse_whenCompletedButExpired() throws Throwable {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");

        SystemRequestDedup row = new SystemRequestDedup();
        row.setId("sig-expired");
        row.setStatus(Status.COMPLETED);
        row.setExpiresAt(now.minusSeconds(10));
        row.setResponseJson("\"stale\"");

        given(repository.findBySignatureForUpdate("sig-expired")).willReturn(Optional.of(row));

        Object result = service.lockAndExecute("sig-expired", String.class, now, now.plusSeconds(30),
                () -> "fresh");

        assertThat(result).isEqualTo("fresh");
        ArgumentCaptor<SystemRequestDedup> captor = ArgumentCaptor.forClass(SystemRequestDedup.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getResponseJson()).isEqualTo("\"fresh\"");
    }

    @Test
    void lockAndExecute_throwsIfRowMissingAfterClaim() {
        given(repository.findBySignatureForUpdate("sig-gone")).willReturn(Optional.empty());

        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.lockAndExecute("sig-gone", String.class, now, now.plusSeconds(30), () -> "x"));
    }

    @Test
    void lockAndExecute_propagatesWorkExceptionsForRollback() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-15T00:00:00Z");

        SystemRequestDedup row = new SystemRequestDedup();
        row.setId("sig-fail");
        row.setStatus(Status.PENDING);
        row.setExpiresAt(now.plusSeconds(30));
        given(repository.findBySignatureForUpdate("sig-fail")).willReturn(Optional.of(row));

        RuntimeException workBoom = new RuntimeException("mutation failed");

        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.lockAndExecute("sig-fail", String.class, now, now.plusSeconds(30),
                        () -> { throw workBoom; }));

        assertThat(thrown).isSameAs(workBoom);
        verify(repository, never()).save(any());
    }
}
