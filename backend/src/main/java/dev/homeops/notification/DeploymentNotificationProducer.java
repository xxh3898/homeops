package dev.homeops.notification;

import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeploymentNotificationProducer {
    private static final int SHORT_COMMIT_LENGTH = 12;

    private final NotificationOutbox outbox;

    DeploymentNotificationProducer(NotificationOutbox outbox) {
        this.outbox = outbox;
    }

    public void recordInitial(UUID deploymentId, DeploymentIngestionRequest request) {
        DeploymentEvent event = switch (request.status()) {
            case REQUESTED, RUNNING -> DeploymentEvent.STARTED;
            case SUCCESS -> DeploymentEvent.SUCCEEDED;
            case FAILED -> DeploymentEvent.FAILED;
            case ROLLED_BACK -> DeploymentEvent.ROLLED_BACK;
            case CANCELLED -> DeploymentEvent.CANCELLED;
        };
        enqueue(deploymentId, request, event);
    }

    public void recordTransition(UUID deploymentId, DeploymentIngestionRequest request) {
        DeploymentEvent event = switch (request.status()) {
            case SUCCESS -> DeploymentEvent.SUCCEEDED;
            case FAILED -> DeploymentEvent.FAILED;
            case ROLLED_BACK -> DeploymentEvent.ROLLED_BACK;
            case CANCELLED -> DeploymentEvent.CANCELLED;
            case REQUESTED, RUNNING -> null;
        };
        if (event != null) {
            enqueue(deploymentId, request, event);
        }
    }

    private void enqueue(
            UUID deploymentId,
            DeploymentIngestionRequest request,
            DeploymentEvent event) {
        Instant occurredAt = event == DeploymentEvent.STARTED || request.finishedAt() == null
                ? request.startedAt() : request.finishedAt();
        outbox.enqueue(new NotificationIntent(
                NotificationSourceType.DEPLOYMENT,
                deploymentId,
                event.severity,
                event.code,
                "deployment:" + deploymentId + ":" + event.code,
                null,
                occurredAt,
                new NotificationPayload(
                        event.code,
                        event.title,
                        event.summary,
                        List.of(
                                new NotificationField("Project", request.project(), true),
                                new NotificationField("Environment", request.environment(), true),
                                new NotificationField("Commit", shortCommit(request.commitSha()), true),
                                new NotificationField("Status", request.status().name(), true)),
                        occurredAt)));
    }

    private static String shortCommit(String commitSha) {
        return commitSha.substring(0, SHORT_COMMIT_LENGTH);
    }

    private enum DeploymentEvent {
        STARTED(
                "DEPLOYMENT_STARTED",
                NotificationSeverity.INFO,
                "Deployment started",
                "A deployment entered its active lifecycle."),
        SUCCEEDED(
                "DEPLOYMENT_SUCCEEDED",
                NotificationSeverity.INFO,
                "Deployment succeeded",
                "A deployment reached a successful terminal state."),
        FAILED(
                "DEPLOYMENT_FAILED",
                NotificationSeverity.CRITICAL,
                "Deployment failed",
                "A deployment reached a failed terminal state."),
        ROLLED_BACK(
                "DEPLOYMENT_ROLLED_BACK",
                NotificationSeverity.WARNING,
                "Deployment rolled back",
                "A deployment reached a rolled-back terminal state."),
        CANCELLED(
                "DEPLOYMENT_CANCELLED",
                NotificationSeverity.WARNING,
                "Deployment cancelled",
                "A deployment reached a cancelled terminal state.");

        private final String code;
        private final NotificationSeverity severity;
        private final String title;
        private final String summary;

        DeploymentEvent(String code, NotificationSeverity severity, String title, String summary) {
            this.code = code;
            this.severity = severity;
            this.title = title;
            this.summary = summary;
        }
    }
}
