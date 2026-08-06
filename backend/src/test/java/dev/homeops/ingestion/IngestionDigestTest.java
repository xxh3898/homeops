package dev.homeops.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.ingestion.api.DeploymentIngestionRequest;
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

    private static DeploymentIngestionRequest request(String project, String environment) {
        return new DeploymentIngestionRequest("deploy-1", project, environment, "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, Instant.parse("2026-08-06T01:00:00Z"),
                null, null, null, "github-actions", "123", null, false);
    }
}
