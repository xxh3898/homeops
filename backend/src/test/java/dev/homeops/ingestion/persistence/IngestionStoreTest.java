package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class IngestionStoreTest {
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void should_updateBackupLocation_when_terminalEventProvidesIt() {
        BackupIngestionStore store = new BackupIngestionStore(jdbcTemplate);
        var request = new BackupIngestionRequest("backup-1", "homeops", "POSTGRESQL",
                "homeops/2026-08-06.dump", BackupIngestionRequest.BackupStatus.SUCCESS,
                Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T01:01:00Z"),
                1024L, null, null, null, null);

        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        boolean updated = store.update(request, "digest", BackupIngestionRequest.BackupStatus.RUNNING);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(updated).isTrue();
        assertThat(sql.getValue()).contains("logical_location = ?", "WHERE event_key = ? AND status = ?");
        assertThat(arguments.getValue()).startsWith("homeops/2026-08-06.dump", "SUCCESS")
                .endsWith("backup-1", "RUNNING");
    }

    @Test
    void should_updateDeploymentMetadata_when_terminalEventProvidesIt() {
        DeploymentIngestionStore store = new DeploymentIngestionStore(jdbcTemplate);
        var request = new DeploymentIngestionRequest("deploy-1", "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456",
                "1111111111111111111111111111111111111111",
                DeploymentIngestionRequest.DeploymentStatus.SUCCESS,
                Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T01:01:00Z"),
                null, null, "github-actions", "123", "https://example.invalid/runs/123", false);

        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        boolean updated = store.update(request, "digest", DeploymentIngestionRequest.DeploymentStatus.RUNNING);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(updated).isTrue();
        assertThat(sql.getValue()).contains("image_tag = ?", "workflow_run_url = ?",
                "WHERE event_key = ? AND status = ?");
        assertThat(arguments.getValue()).startsWith("main", "sha-0123456",
                "1111111111111111111111111111111111111111", "SUCCESS")
                .endsWith("deploy-1", "RUNNING");
    }

    @Test
    void should_reportConditionalUpdateMiss_when_storedStatusChanged() {
        DeploymentIngestionStore store = new DeploymentIngestionStore(jdbcTemplate);
        var request = new DeploymentIngestionRequest("deploy-1", "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.SUCCESS,
                Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T01:01:00Z"),
                null, null, "github-actions", "123", null, false);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(0);

        boolean updated = store.update(request, "digest", DeploymentIngestionRequest.DeploymentStatus.RUNNING);

        assertThat(updated).isFalse();
    }
}
