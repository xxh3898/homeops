package dev.homeops.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.persistence.BackupIngestionStore;
import dev.homeops.ingestion.persistence.DeploymentIngestionStore;
import dev.homeops.ingestion.persistence.IngestionEventKeyLedgerStore;
import dev.homeops.ingestion.persistence.IngestionEventKeyLedgerStore.SourceType;
import dev.homeops.notification.BackupNotificationProducer;
import dev.homeops.notification.DeploymentNotificationProducer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {
    @Mock private DeploymentIngestionStore deployments;
    @Mock private BackupIngestionStore backups;
    @Mock private IngestionEventKeyLedgerStore eventKeys;
    @Mock private DeploymentNotificationProducer deploymentNotifications;
    @Mock private BackupNotificationProducer backupNotifications;
    @InjectMocks private IngestionService service;
    @Spy private final IngestionDigest digest = new IngestionDigest();

    @Test
    void should_markDuplicate_when_deploymentPayloadMatchesStoredDigest() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(Optional.of(
                deploymentStored(id, "RUNNING", requestDigest)));

        var result = service.acceptDeployment(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verify(deployments, never()).update(any(), any(), any());
        verifyNoInteractions(eventKeys);
        verifyNoInteractions(deploymentNotifications);
    }

    @Test
    void should_acceptDeployment_when_eventKeyIsInsertedFirst() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000011");
        when(deployments.find(request.eventKey())).thenReturn(Optional.empty());
        when(eventKeys.reserve(SourceType.DEPLOYMENT, request.eventKey())).thenReturn(true);
        when(deployments.insertIfAbsent(eq(request), any())).thenReturn(Optional.of(id));

        var result = service.acceptDeployment(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, false));
        verify(eventKeys).reserve(SourceType.DEPLOYMENT, request.eventKey());
        verify(deploymentNotifications).recordInitial(id, request);
    }

    @Test
    void should_acceptWinningDeployment_when_concurrentLedgerReservationLoses() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000012");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(Optional.empty(), Optional.of(
                deploymentStored(id, "RUNNING", requestDigest)));
        when(eventKeys.reserve(SourceType.DEPLOYMENT, request.eventKey())).thenReturn(false);

        var result = service.acceptDeployment(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verify(deployments, never()).insertIfAbsent(any(), any());
        verifyNoInteractions(deploymentNotifications);
    }

    @Test
    void should_rejectDeployment_when_ledgerExistsWithoutBusinessRow() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.RUNNING);
        when(deployments.find(request.eventKey())).thenReturn(Optional.empty());
        when(eventKeys.reserve(SourceType.DEPLOYMENT, request.eventKey())).thenReturn(false);

        assertThatThrownBy(() -> service.acceptDeployment(request))
                .isInstanceOf(EventKeyConflictException.class);

        verify(deployments, never()).insertIfAbsent(any(), any());
        verifyNoInteractions(deploymentNotifications);
    }

    @Test
    void should_updateDeployment_when_runningTransitionsToSuccess() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000002");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(Optional.of(
                deploymentStored(id, "RUNNING", "different")));
        when(deployments.update(request, requestDigest,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING)).thenReturn(true);

        var result = service.acceptDeployment(request);

        assertThat(result.duplicate()).isFalse();
        verify(deployments).update(eq(request), eq(requestDigest),
                eq(DeploymentIngestionRequest.DeploymentStatus.RUNNING));
        verify(deploymentNotifications).recordTransition(id, request);
    }

    @Test
    void should_rejectDeployment_when_terminalStateChanges() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.FAILED);
        when(deployments.find(request.eventKey())).thenReturn(Optional.of(
                deploymentStored(UUID.randomUUID(), "SUCCESS", "different")));

        assertThatThrownBy(() -> service.acceptDeployment(request))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
    }

    @Test
    void should_rejectDeployment_when_eventKeyBelongsToDifferentLifecycle() {
        var request = new DeploymentIngestionRequest("deploy-1", "another-project", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.SUCCESS, Instant.parse("2026-08-06T01:00:00Z"),
                Instant.parse("2026-08-06T01:01:00Z"), null, null, "github-actions", "123", null, false);
        when(deployments.find(request.eventKey())).thenReturn(Optional.of(
                deploymentStored(UUID.randomUUID(), "RUNNING", "different")));

        assertThatThrownBy(() -> service.acceptDeployment(request))
                .isInstanceOf(EventKeyConflictException.class);
    }

    @Test
    void should_rejectBackup_when_terminalStateChanges() {
        var request = backup(BackupIngestionRequest.BackupStatus.FAILED);
        when(backups.find(request.eventKey())).thenReturn(Optional.of(
                backupStored(UUID.randomUUID(), "SUCCESS", "different")));

        assertThatThrownBy(() -> service.acceptBackup(request))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
    }

    @Test
    void should_acceptBackup_when_eventKeyIsInsertedFirst() {
        var request = backup(BackupIngestionRequest.BackupStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000018");
        when(backups.find(request.eventKey())).thenReturn(Optional.empty());
        when(eventKeys.reserve(SourceType.BACKUP, request.eventKey())).thenReturn(true);
        when(backups.insertIfAbsent(eq(request), any())).thenReturn(Optional.of(id));

        var result = service.acceptBackup(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, false));
        verify(eventKeys).reserve(SourceType.BACKUP, request.eventKey());
        verify(backupNotifications).recordInitial(id, request);
    }

    @Test
    void should_acceptWinningBackup_when_concurrentLedgerReservationLoses() {
        var request = backup(BackupIngestionRequest.BackupStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000021");
        String requestDigest = digest.calculate(request);
        when(backups.find(request.eventKey())).thenReturn(Optional.empty(), Optional.of(
                backupStored(id, "RUNNING", requestDigest)));
        when(eventKeys.reserve(SourceType.BACKUP, request.eventKey())).thenReturn(false);

        var result = service.acceptBackup(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verify(backups, never()).insertIfAbsent(any(), any());
        verifyNoInteractions(backupNotifications);
    }

    @Test
    void should_rejectBackup_when_ledgerExistsWithoutBusinessRow() {
        var request = backup(BackupIngestionRequest.BackupStatus.RUNNING);
        when(backups.find(request.eventKey())).thenReturn(Optional.empty());
        when(eventKeys.reserve(SourceType.BACKUP, request.eventKey())).thenReturn(false);

        assertThatThrownBy(() -> service.acceptBackup(request))
                .isInstanceOf(EventKeyConflictException.class);

        verify(backups, never()).insertIfAbsent(any(), any());
        verifyNoInteractions(backupNotifications);
    }

    @Test
    void should_markDuplicate_when_backupPayloadMatchesStoredDigest() {
        var request = backup(BackupIngestionRequest.BackupStatus.RUNNING);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000019");
        String requestDigest = digest.calculate(request);
        when(backups.find(request.eventKey())).thenReturn(Optional.of(
                backupStored(id, "RUNNING", requestDigest)));

        var result = service.acceptBackup(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verifyNoInteractions(eventKeys);
        verifyNoInteractions(backupNotifications);
    }

    @Test
    void should_rejectBackup_when_runningPayloadConflicts() {
        var request = backup(BackupIngestionRequest.BackupStatus.RUNNING);
        when(backups.find(request.eventKey())).thenReturn(Optional.of(
                backupStored(UUID.randomUUID(), "RUNNING", "different")));

        assertThatThrownBy(() -> service.acceptBackup(request))
                .isInstanceOf(EventKeyConflictException.class);
    }

    @Test
    void should_updateBackup_when_terminalPayloadAddsLogicalLocation() {
        var request = backup(BackupIngestionRequest.BackupStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000013");
        String requestDigest = digest.calculate(request);
        when(backups.find(request.eventKey())).thenReturn(Optional.of(
                backupStored(id, "RUNNING", "different")));
        when(backups.update(request, requestDigest, BackupIngestionRequest.BackupStatus.RUNNING)).thenReturn(true);

        var result = service.acceptBackup(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, false));
        verify(backups).update(request, requestDigest, BackupIngestionRequest.BackupStatus.RUNNING);
        verify(backupNotifications).recordTransition(id, request);
    }

    @Test
    void should_markDuplicate_when_concurrentDeploymentUpdateAlreadyStoredMatchingTerminalEvent() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000015");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(
                Optional.of(deploymentStored(id, "RUNNING", "running-digest")),
                Optional.of(deploymentStored(id, "SUCCESS", requestDigest)));
        when(deployments.update(request, requestDigest,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING)).thenReturn(false);

        var result = service.acceptDeployment(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verifyNoInteractions(deploymentNotifications);
    }

    @Test
    void should_rejectDeployment_when_concurrentTerminalEventChangedStoredState() {
        var request = deployment(DeploymentIngestionRequest.DeploymentStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000016");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(
                Optional.of(deploymentStored(id, "RUNNING", "running-digest")),
                Optional.of(deploymentStored(id, "FAILED", "other-terminal-digest")));
        when(deployments.update(request, requestDigest,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING)).thenReturn(false);

        assertThatThrownBy(() -> service.acceptDeployment(request))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
    }

    @Test
    void should_rejectBackup_when_concurrentTerminalEventChangedStoredState() {
        var request = backup(BackupIngestionRequest.BackupStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000017");
        String requestDigest = digest.calculate(request);
        when(backups.find(request.eventKey())).thenReturn(
                Optional.of(backupStored(id, "RUNNING", "running-digest")),
                Optional.of(backupStored(id, "FAILED", "other-terminal-digest")));
        when(backups.update(request, requestDigest, BackupIngestionRequest.BackupStatus.RUNNING)).thenReturn(false);

        assertThatThrownBy(() -> service.acceptBackup(request))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
        verifyNoInteractions(backupNotifications);
    }

    @Test
    void should_markDuplicate_when_concurrentBackupUpdateAlreadyStoredMatchingTerminalEvent() {
        var request = backup(BackupIngestionRequest.BackupStatus.SUCCESS);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000020");
        String requestDigest = digest.calculate(request);
        when(backups.find(request.eventKey())).thenReturn(
                Optional.of(backupStored(id, "RUNNING", "running-digest")),
                Optional.of(backupStored(id, "SUCCESS", requestDigest)));
        when(backups.update(request, requestDigest, BackupIngestionRequest.BackupStatus.RUNNING)).thenReturn(false);

        var result = service.acceptBackup(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
        verifyNoInteractions(backupNotifications);
    }

    @Test
    void should_markDuplicate_when_deploymentRetryUsesNanosecondTimestamp() {
        Instant startedAt = Instant.parse("2026-08-06T01:00:00.123456789Z");
        var request = new DeploymentIngestionRequest("deploy-nanos", "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, startedAt,
                Instant.parse("2026-08-06T01:01:00Z"), null, null, "github-actions", "123", null, false);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000014");
        String requestDigest = digest.calculate(request);
        when(deployments.find(request.eventKey())).thenReturn(Optional.of(new DeploymentIngestionStore.StoredDeployment(
                id, "homeops", "production", "0123456789012345678901234567890123456789",
                startedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS), "RUNNING", requestDigest)));

        var result = service.acceptDeployment(request);

        assertThat(result).isEqualTo(new dev.homeops.ingestion.api.IngestionAcceptedResponse(id, true));
    }

    private static DeploymentIngestionRequest deployment(DeploymentIngestionRequest.DeploymentStatus status) {
        return new DeploymentIngestionRequest("deploy-1", "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null, status,
                Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T01:01:00Z"),
                null, null, "github-actions", "123", null, false);
    }

    private static BackupIngestionRequest backup(BackupIngestionRequest.BackupStatus status) {
        return new BackupIngestionRequest("backup-1", "homeops", "POSTGRESQL", "homeops/2026-08-06.dump",
                status, Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T01:01:00Z"),
                1024L, null, null, null, null);
    }

    private static DeploymentIngestionStore.StoredDeployment deploymentStored(UUID id, String status, String digest) {
        return new DeploymentIngestionStore.StoredDeployment(id, "homeops", "production",
                "0123456789012345678901234567890123456789", Instant.parse("2026-08-06T01:00:00Z"), status, digest);
    }

    private static BackupIngestionStore.StoredBackup backupStored(UUID id, String status, String digest) {
        return new BackupIngestionStore.StoredBackup(id, "homeops", "POSTGRESQL",
                Instant.parse("2026-08-06T01:00:00Z"), status, digest);
    }
}
