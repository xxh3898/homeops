package dev.homeops.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IngestionDigestTest {
    private final IngestionDigest digest = new IngestionDigest();

    @Test
    void should_distinguishRequests_when_recordToStringIsAmbiguous() {
        DeploymentIngestionRequest first = request("p, environment=e", "x");
        DeploymentIngestionRequest second = request("p", "e, environment=x");

        assertThat(first.toString()).isEqualTo(second.toString());
        assertThat(digest.calculate(first)).isNotEqualTo(digest.calculate(second));
    }

    @Test
    void should_useCanonicalPostgresqlTimestamp_when_deploymentDigestIsCalculated() {
        DeploymentIngestionRequest subMicrosecond = requestWithStartedAt("2026-08-06T01:00:00.123456499Z");
        DeploymentIngestionRequest exactMicrosecond = requestWithStartedAt("2026-08-06T01:00:00.123456000Z");

        assertThat(digest.calculate(subMicrosecond)).isEqualTo(digest.calculate(exactMicrosecond));
    }

    @Test
    void should_useCanonicalPostgresqlTimestamp_when_backupDigestIsCalculated() {
        BackupIngestionRequest subMicrosecond = backupWithStartedAt("2026-08-06T01:00:00.123456789Z");
        BackupIngestionRequest roundedMicrosecond = backupWithStartedAt("2026-08-06T01:00:00.123457000Z");

        assertThat(digest.calculate(subMicrosecond)).isEqualTo(digest.calculate(roundedMicrosecond));
    }

    private static DeploymentIngestionRequest request(String project, String environment) {
        return new DeploymentIngestionRequest("deploy-1", project, environment, "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, Instant.parse("2026-08-06T01:00:00Z"),
                null, null, null, "github-actions", "123", null, false);
    }

    private static DeploymentIngestionRequest requestWithStartedAt(String startedAt) {
        return new DeploymentIngestionRequest("deploy-precision", "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, Instant.parse(startedAt), null,
                null, null, "github-actions", "123", null, false);
    }

    private static BackupIngestionRequest backupWithStartedAt(String startedAt) {
        return new BackupIngestionRequest("backup-precision", "homeops", "POSTGRESQL", "backups/latest.dump",
                BackupIngestionRequest.BackupStatus.RUNNING, Instant.parse(startedAt), null,
                null, null, null, null, null);
    }
}
