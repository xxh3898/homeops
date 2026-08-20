package dev.homeops.ingestion;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.persistence.BackupIngestionStore;
import dev.homeops.ingestion.persistence.DeploymentIngestionStore;
import dev.homeops.notification.BackupNotificationProducer;
import dev.homeops.notification.DeploymentNotificationProducer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final DeploymentIngestionStore deployments;
    private final BackupIngestionStore backups;
    private final IngestionDigest digest;
    private final DeploymentNotificationProducer deploymentNotifications;
    private final BackupNotificationProducer backupNotifications;

    public IngestionService(
            DeploymentIngestionStore deployments,
            BackupIngestionStore backups,
            IngestionDigest digest,
            DeploymentNotificationProducer deploymentNotifications,
            BackupNotificationProducer backupNotifications) {
        this.deployments = deployments;
        this.backups = backups;
        this.digest = digest;
        this.deploymentNotifications = deploymentNotifications;
        this.backupNotifications = backupNotifications;
    }

    @Transactional
    public IngestionAcceptedResponse acceptDeployment(DeploymentIngestionRequest request) {
        DeploymentIngestionRequest canonicalRequest = IngestionTimestampCanonicalizer.canonicalize(request);
        String requestDigest = digest.calculate(canonicalRequest);
        var existing = deployments.find(canonicalRequest.eventKey());
        if (existing.isEmpty()) {
            var inserted = deployments.insertIfAbsent(canonicalRequest, requestDigest);
            if (inserted.isPresent()) {
                deploymentNotifications.recordInitial(inserted.get(), canonicalRequest);
                return new IngestionAcceptedResponse(inserted.get(), false);
            }
            existing = deployments.find(canonicalRequest.eventKey());
        }
        var stored = existing.orElseThrow(() -> new EventKeyConflictException(canonicalRequest.eventKey()));
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        if (!stored.matchesLifecycle(canonicalRequest)) throw new EventKeyConflictException(canonicalRequest.eventKey());
        DeploymentIngestionRequest.DeploymentStatus current = DeploymentIngestionRequest.DeploymentStatus.valueOf(stored.status());
        if (!isAllowed(current, canonicalRequest.status())) {
            throw new InvalidIngestionStateTransitionException(current.name(), canonicalRequest.status().name());
        }
        if (deployments.update(canonicalRequest, requestDigest, current)) {
            deploymentNotifications.recordTransition(stored.id(), canonicalRequest);
            return new IngestionAcceptedResponse(stored.id(), false);
        }
        return resolveDeploymentAfterConditionalUpdateMiss(canonicalRequest, requestDigest);
    }

    @Transactional
    public IngestionAcceptedResponse acceptBackup(BackupIngestionRequest request) {
        BackupIngestionRequest canonicalRequest = IngestionTimestampCanonicalizer.canonicalize(request);
        String requestDigest = digest.calculate(canonicalRequest);
        var existing = backups.find(canonicalRequest.eventKey());
        if (existing.isEmpty()) {
            var inserted = backups.insertIfAbsent(canonicalRequest, requestDigest);
            if (inserted.isPresent()) {
                backupNotifications.recordInitial(inserted.get(), canonicalRequest);
                return new IngestionAcceptedResponse(inserted.get(), false);
            }
            existing = backups.find(canonicalRequest.eventKey());
        }
        var stored = existing.orElseThrow(() -> new EventKeyConflictException(canonicalRequest.eventKey()));
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        if (!stored.matchesLifecycle(canonicalRequest)) throw new EventKeyConflictException(canonicalRequest.eventKey());
        BackupIngestionRequest.BackupStatus current = BackupIngestionRequest.BackupStatus.valueOf(stored.status());
        if (current != BackupIngestionRequest.BackupStatus.RUNNING) {
            throw new InvalidIngestionStateTransitionException(current.name(), canonicalRequest.status().name());
        }
        if (canonicalRequest.status() == BackupIngestionRequest.BackupStatus.RUNNING) {
            throw new EventKeyConflictException(canonicalRequest.eventKey());
        }
        if (backups.update(canonicalRequest, requestDigest, current)) {
            backupNotifications.recordTransition(stored.id(), canonicalRequest);
            return new IngestionAcceptedResponse(stored.id(), false);
        }
        return resolveBackupAfterConditionalUpdateMiss(canonicalRequest, requestDigest);
    }

    private IngestionAcceptedResponse resolveDeploymentAfterConditionalUpdateMiss(
            DeploymentIngestionRequest request, String requestDigest) {
        var stored = deployments.find(request.eventKey())
                .orElseThrow(() -> new EventKeyConflictException(request.eventKey()));
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        if (!stored.matchesLifecycle(request)) throw new EventKeyConflictException(request.eventKey());
        DeploymentIngestionRequest.DeploymentStatus current = DeploymentIngestionRequest.DeploymentStatus.valueOf(stored.status());
        throw new InvalidIngestionStateTransitionException(current.name(), request.status().name());
    }

    private IngestionAcceptedResponse resolveBackupAfterConditionalUpdateMiss(
            BackupIngestionRequest request, String requestDigest) {
        var stored = backups.find(request.eventKey())
                .orElseThrow(() -> new EventKeyConflictException(request.eventKey()));
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        if (!stored.matchesLifecycle(request)) throw new EventKeyConflictException(request.eventKey());
        BackupIngestionRequest.BackupStatus current = BackupIngestionRequest.BackupStatus.valueOf(stored.status());
        throw new InvalidIngestionStateTransitionException(current.name(), request.status().name());
    }

    private static boolean isAllowed(DeploymentIngestionRequest.DeploymentStatus current,
            DeploymentIngestionRequest.DeploymentStatus requested) {
        return switch (current) {
            case REQUESTED -> requested == DeploymentIngestionRequest.DeploymentStatus.RUNNING
                    || requested == DeploymentIngestionRequest.DeploymentStatus.FAILED
                    || requested == DeploymentIngestionRequest.DeploymentStatus.CANCELLED;
            case RUNNING -> requested == DeploymentIngestionRequest.DeploymentStatus.SUCCESS
                    || requested == DeploymentIngestionRequest.DeploymentStatus.FAILED
                    || requested == DeploymentIngestionRequest.DeploymentStatus.ROLLED_BACK
                    || requested == DeploymentIngestionRequest.DeploymentStatus.CANCELLED;
            default -> false;
        };
    }
}
