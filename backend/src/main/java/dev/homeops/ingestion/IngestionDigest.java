package dev.homeops.ingestion;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class IngestionDigest {
    public String calculate(Object request) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRepresentation(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonicalRepresentation(Object request) {
        StringBuilder value = new StringBuilder();
        if (request instanceof DeploymentIngestionRequest deployment) {
            deployment = IngestionTimestampCanonicalizer.canonicalize(deployment);
            append(value, "deployment-v1");
            append(value, deployment.eventKey());
            append(value, deployment.project());
            append(value, deployment.environment());
            append(value, deployment.branch());
            append(value, deployment.commitSha());
            append(value, deployment.imageTag());
            append(value, deployment.previousCommitSha());
            append(value, stringify(deployment.status()));
            append(value, stringify(deployment.startedAt()));
            append(value, stringify(deployment.finishedAt()));
            append(value, deployment.failureStage());
            append(value, deployment.failureSummary());
            append(value, deployment.actor());
            append(value, deployment.workflowRunId());
            append(value, deployment.workflowRunUrl());
            append(value, Boolean.toString(deployment.rollback()));
            return value.toString();
        }
        if (request instanceof BackupIngestionRequest backup) {
            backup = IngestionTimestampCanonicalizer.canonicalize(backup);
            append(value, "backup-v1");
            append(value, backup.eventKey());
            append(value, backup.project());
            append(value, backup.databaseType());
            append(value, backup.logicalLocation());
            append(value, stringify(backup.status()));
            append(value, stringify(backup.startedAt()));
            append(value, stringify(backup.finishedAt()));
            append(value, stringify(backup.sizeBytes()));
            append(value, stringify(backup.expiresAt()));
            append(value, backup.failureSummary());
            append(value, stringify(backup.restoreTestedAt()));
            append(value, backup.restoreTestStatus());
            return value.toString();
        }
        throw new IllegalArgumentException("Unsupported ingestion request type");
    }

    private static String stringify(Object value) {
        return value == null ? null : value.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        target.append('|');
    }
}
