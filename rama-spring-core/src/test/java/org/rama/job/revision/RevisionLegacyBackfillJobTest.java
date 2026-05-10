package org.rama.job.revision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionLegacyBackfillJobTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TransactionTemplate transactionTemplate;

    private RevisionLegacyBackfillJob job;

    @BeforeEach
    void setUp() {
        job = new RevisionLegacyBackfillJob(jdbcTemplate, transactionTemplate);
    }

    private void runTxInline() {
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.<TransactionCallback<Integer>>any()))
                .thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    @Test
    void runBatch_shouldInsertAndDeleteWithMatchingCounts() {
        runTxInline();
        when(jdbcTemplate.update(startsWith("INSERT INTO dbo.revision_archive"), anyString(), anyString()))
                .thenReturn(42);
        when(jdbcTemplate.update(startsWith("DELETE FROM dbo.revision_legacy"), anyString(), anyString()))
                .thenReturn(42);

        job.runBatch("revision_legacy", "revision_archive", YearMonth.of(2025, 3));

        verify(jdbcTemplate).update(
                startsWith("INSERT INTO dbo.revision_archive"),
                eq("2025-03-01T00:00Z"),
                eq("2025-04-01T00:00Z"));
        verify(jdbcTemplate).update(
                startsWith("DELETE FROM dbo.revision_legacy"),
                eq("2025-03-01T00:00Z"),
                eq("2025-04-01T00:00Z"));
    }

    @Test
    void runBatch_shouldRollback_whenInsertDeleteMismatch() {
        runTxInline();
        when(jdbcTemplate.update(startsWith("INSERT INTO dbo.revision_archive"), anyString(), anyString()))
                .thenReturn(42);
        when(jdbcTemplate.update(startsWith("DELETE FROM dbo.revision_legacy"), anyString(), anyString()))
                .thenReturn(40);

        assertThatThrownBy(() -> job.runBatch("revision_legacy", "revision_archive", YearMonth.of(2025, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Backfill mismatch")
                .hasMessageContaining("inserted=42")
                .hasMessageContaining("deleted=40");
    }

    @Test
    void safeIdent_shouldRejectInjection() {
        assertThat(RevisionLegacyBackfillJob.safeIdent("revision_legacy")).isEqualTo("revision_legacy");
        assertThatThrownBy(() -> RevisionLegacyBackfillJob.safeIdent("revision_legacy; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RevisionLegacyBackfillJob.safeIdent("revision-legacy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RevisionLegacyBackfillJob.safeIdent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
