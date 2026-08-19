package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeploymentNotificationProducerTest {
    private static final UUID DEPLOYMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000037");
    private static final Instant STARTED_AT = Instant.parse("2026-08-19T01:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-19T01:05:00Z");

    @Mock private NotificationOutbox outbox;
    private DeploymentNotificationProducer producer;

    @BeforeEach
    void createProducer() {
        producer = new DeploymentNotificationProducer(outbox);
    }

    @ParameterizedTest
    @CsvSource({
        "REQUESTED, DEPLOYMENT_STARTED, INFO",
        "RUNNING, DEPLOYMENT_STARTED, INFO",
        "SUCCESS, DEPLOYMENT_SUCCEEDED, INFO",
        "FAILED, DEPLOYMENT_FAILED, CRITICAL",
        "ROLLED_BACK, DEPLOYMENT_ROLLED_BACK, WARNING",
        "CANCELLED, DEPLOYMENT_CANCELLED, WARNING"
    })
    void should_mapFirstInsertToOneIntent_when_anyAcceptedStatusIsInitial(
            DeploymentIngestionRequest.DeploymentStatus status,
            String eventCode,
            NotificationSeverity severity) {
        producer.recordInitial(DEPLOYMENT_ID, deployment(status));

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.DEPLOYMENT);
        assertThat(intent.sourceId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(intent.eventType()).isEqualTo(eventCode);
        assertThat(intent.severity()).isEqualTo(severity);
        assertThat(intent.payload().eventCode()).isEqualTo(eventCode);
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("deployment:" + DEPLOYMENT_ID + ":" + eventCode);
        assertThat(intent.occurredAt()).isEqualTo(
                eventCode.equals("DEPLOYMENT_STARTED") ? STARTED_AT : FINISHED_AT);
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, DEPLOYMENT_SUCCEEDED, INFO",
        "FAILED, DEPLOYMENT_FAILED, CRITICAL",
        "ROLLED_BACK, DEPLOYMENT_ROLLED_BACK, WARNING",
        "CANCELLED, DEPLOYMENT_CANCELLED, WARNING"
    })
    void should_mapTerminalWinnerToIntent_when_transitionIsAccepted(
            DeploymentIngestionRequest.DeploymentStatus status,
            String eventCode,
            NotificationSeverity severity) {
        producer.recordTransition(DEPLOYMENT_ID, deployment(status));

        NotificationIntent intent = capturedIntent();
        assertThat(intent.eventType()).isEqualTo(eventCode);
        assertThat(intent.severity()).isEqualTo(severity);
        assertThat(intent.occurredAt()).isEqualTo(FINISHED_AT);
    }

    @ParameterizedTest
    @CsvSource({"REQUESTED", "RUNNING"})
    void should_notCreateIntent_when_existingDeploymentMovesWithinNonterminalStates(
            DeploymentIngestionRequest.DeploymentStatus status) {
        producer.recordTransition(DEPLOYMENT_ID, deployment(status));

        verify(outbox, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_includeOnlyAllowlistedBoundedFields_when_requestContainsPrivateMetadata() {
        DeploymentIngestionRequest request = new DeploymentIngestionRequest(
                "deploy-private-event-key",
                "homeops",
                "production",
                "private-branch",
                "0123456789abcdef0123456789abcdef01234567",
                "private-image-tag",
                "fedcba9876543210fedcba9876543210fedcba98",
                DeploymentIngestionRequest.DeploymentStatus.FAILED,
                STARTED_AT,
                FINISHED_AT,
                "private-failure-stage",
                "private-failure-summary",
                "private-actor",
                "private-run-id",
                "https://private.invalid/workflow",
                false);

        producer.recordInitial(DEPLOYMENT_ID, request);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.payload().fields())
                .extracting(NotificationField::name)
                .containsExactly("Project", "Environment", "Commit", "Status");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly("homeops", "production", "0123456789ab", "FAILED")
                .doesNotContain(
                        request.eventKey(), request.branch(), request.commitSha(), request.imageTag(),
                        request.previousCommitSha(), request.failureStage(), request.failureSummary(),
                        request.actor(), request.workflowRunId(), request.workflowRunUrl());
    }

    @Test
    void should_useStartedAt_when_initialTerminalEventHasNoFinishedAt() {
        DeploymentIngestionRequest request = deployment(
                DeploymentIngestionRequest.DeploymentStatus.FAILED, null);

        producer.recordInitial(DEPLOYMENT_ID, request);

        assertThat(capturedIntent().occurredAt()).isEqualTo(STARTED_AT);
    }

    private NotificationIntent capturedIntent() {
        ArgumentCaptor<NotificationIntent> captor = ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }

    private static DeploymentIngestionRequest deployment(
            DeploymentIngestionRequest.DeploymentStatus status) {
        return deployment(status, FINISHED_AT);
    }

    private static DeploymentIngestionRequest deployment(
            DeploymentIngestionRequest.DeploymentStatus status,
            Instant finishedAt) {
        return new DeploymentIngestionRequest(
                "deploy-37",
                "homeops",
                "production",
                "main",
                "0123456789abcdef0123456789abcdef01234567",
                "sha-0123456",
                null,
                status,
                STARTED_AT,
                finishedAt,
                null,
                null,
                "github-actions",
                "37",
                null,
                false);
    }
}
