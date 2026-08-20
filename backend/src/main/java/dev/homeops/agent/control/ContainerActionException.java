package dev.homeops.agent.control;

import java.net.URI;
import org.springframework.http.HttpStatus;

public final class ContainerActionException extends RuntimeException {
    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final String publicDetail;

    private ContainerActionException(
            HttpStatus status,
            String type,
            String title,
            String publicDetail) {
        super(title);
        this.status = status;
        this.type = URI.create("urn:homeops:problem:" + type);
        this.title = title;
        this.publicDetail = publicDetail;
    }

    public static ContainerActionException invalidRequest() {
        return new ContainerActionException(
                HttpStatus.BAD_REQUEST,
                "container-action-invalid",
                "Invalid container action request",
                "Container action request validation failed");
    }

    public static ContainerActionException originRejected() {
        return new ContainerActionException(
                HttpStatus.FORBIDDEN,
                "container-action-origin-rejected",
                "Container action origin rejected",
                "Container action request origin is not allowed");
    }

    public static ContainerActionException confirmationMismatch() {
        return new ContainerActionException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "container-action-confirmation-mismatch",
                "Container action confirmation rejected",
                "Container action confirmation does not match the requested operation");
    }

    public static ContainerActionException denied() {
        return new ContainerActionException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "container-action-denied",
                "Container action denied",
                "Container action target is not eligible");
    }

    public static ContainerActionException conflict() {
        return new ContainerActionException(
                HttpStatus.CONFLICT,
                "container-action-idempotency-conflict",
                "Container action idempotency conflict",
                "Idempotency key conflicts with an existing operation");
    }

    public static ContainerActionException busy() {
        return new ContainerActionException(
                HttpStatus.CONFLICT,
                "container-action-busy",
                "Container control is busy",
                "Another container control operation is active");
    }

    public static ContainerActionException rateLimited() {
        return new ContainerActionException(
                HttpStatus.TOO_MANY_REQUESTS,
                "container-action-rate-limited",
                "Container action rate limit reached",
                "Too many new container action requests were submitted");
    }

    public static ContainerActionException notFound() {
        return new ContainerActionException(
                HttpStatus.NOT_FOUND,
                "container-action-not-found",
                "Container action not found",
                "The requested container action does not exist");
    }

    public static ContainerActionException unavailable() {
        return new ContainerActionException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "container-action-unavailable",
                "Container action unavailable",
                "Container action service is unavailable");
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String publicDetail() {
        return publicDetail;
    }
}
