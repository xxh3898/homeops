package dev.homeops.ingestion;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.persistence.BackupIngestionStore;
import dev.homeops.ingestion.persistence.DeploymentIngestionStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final DeploymentIngestionStore deployments;
    private final BackupIngestionStore backups;
    private final IngestionDigest digest;

    public IngestionService(DeploymentIngestionStore deployments, BackupIngestionStore backups, IngestionDigest digest) {
        this.deployments = deployments; this.backups = backups; this.digest = digest;
    }

    @Transactional
    public IngestionAcceptedResponse acceptDeployment(DeploymentIngestionRequest request) {
        String requestDigest = digest.calculate(request);
        var existing = deployments.find(request.eventKey());
        if (existing.isEmpty()) {
            try { return new IngestionAcceptedResponse(deployments.insert(request, requestDigest), false); }
            catch (DuplicateKeyException exception) { existing = deployments.find(request.eventKey()); }
        }
        var stored = existing.orElseThrow(() -> new EventKeyConflictException(request.eventKey()));
        if (!stored.matchesLifecycle(request)) throw new EventKeyConflictException(request.eventKey());
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        DeploymentIngestionRequest.DeploymentStatus current = DeploymentIngestionRequest.DeploymentStatus.valueOf(stored.status());
        if (!isAllowed(current, request.status())) throw new InvalidIngestionStateTransitionException(current.name(), request.status().name());
        deployments.update(request, requestDigest);
        return new IngestionAcceptedResponse(stored.id(), false);
    }

    @Transactional
    public IngestionAcceptedResponse acceptBackup(BackupIngestionRequest request) {
        String requestDigest = digest.calculate(request);
        var existing = backups.find(request.eventKey());
        if (existing.isEmpty()) {
            try { return new IngestionAcceptedResponse(backups.insert(request, requestDigest), false); }
            catch (DuplicateKeyException exception) { existing = backups.find(request.eventKey()); }
        }
        var stored = existing.orElseThrow(() -> new EventKeyConflictException(request.eventKey()));
        if (!stored.matchesLifecycle(request)) throw new EventKeyConflictException(request.eventKey());
        if (requestDigest.equals(stored.digest())) return new IngestionAcceptedResponse(stored.id(), true);
        BackupIngestionRequest.BackupStatus current = BackupIngestionRequest.BackupStatus.valueOf(stored.status());
        if (current != BackupIngestionRequest.BackupStatus.RUNNING) throw new InvalidIngestionStateTransitionException(current.name(), request.status().name());
        if (request.status() == BackupIngestionRequest.BackupStatus.RUNNING) throw new EventKeyConflictException(request.eventKey());
        backups.update(request, requestDigest);
        return new IngestionAcceptedResponse(stored.id(), false);
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
