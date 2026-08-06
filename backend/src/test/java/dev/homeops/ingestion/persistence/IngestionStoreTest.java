package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

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

        store.update(request, "digest");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("logical_location = ?");
        assertThat(arguments.getValue()).startsWith("homeops/2026-08-06.dump", "SUCCESS");
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

        store.update(request, "digest");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("image_tag = ?", "workflow_run_url = ?");
        assertThat(arguments.getValue()).startsWith("main", "sha-0123456",
                "1111111111111111111111111111111111111111", "SUCCESS");
    }
}
