package dev.homeops.common;

import dev.homeops.activity.InvalidActivityCursorException;
import dev.homeops.activity.InvalidActivityTypeException;
import dev.homeops.agent.control.ContainerControlRequestGoneException;
import dev.homeops.agent.control.ContainerControlResultRejectedException;
import dev.homeops.agent.control.ContainerActionException;
import dev.homeops.agent.logs.ContainerLogBrokerCapacityException;
import dev.homeops.agent.logs.ContainerLogCapabilityUnavailableException;
import dev.homeops.agent.logs.ContainerLogRequestConflictException;
import dev.homeops.agent.logs.ContainerLogRequestGoneException;
import dev.homeops.agent.logs.ContainerLogRequestTimeoutException;
import dev.homeops.agent.logs.ContainerLogResultRejectedException;
import dev.homeops.agent.logs.ContainerLogRetrievalUnavailableException;
import dev.homeops.agent.logs.ContainerLogsNotAllowedException;
import dev.homeops.agent.logs.InvalidContainerLogTailException;
import dev.homeops.metrics.InvalidMetricHistoryPeriodException;
import dev.homeops.monitoring.SafeServiceUrlPolicy.UnsafeServiceUrlException;
import dev.homeops.monitoring.MonitoredServiceNotFoundException;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ContainerActionException.class)
    ProblemDetail handleContainerAction(ContainerActionException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                exception.status(),
                exception.publicDetail());
        detail.setType(exception.type());
        detail.setTitle(exception.title());
        return detail;
    }

    @ExceptionHandler(AgentSnapshotRejectedException.class)
    ProblemDetail handleRejectedSnapshot(
            AgentSnapshotRejectedException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:agent-snapshot-rejected"));
        detail.setTitle("Agent snapshot rejected");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        return validationProblem(exception.getErrorCount());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        return validationProblem(exception.getAllErrors().size());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return validationProblem(1);
    }

    @ExceptionHandler(InvalidMetricHistoryPeriodException.class)
    ProblemDetail handleInvalidMetricHistoryPeriod() {
        return validationProblem(1);
    }

    @ExceptionHandler(InvalidContainerIdentifierException.class)
    ProblemDetail handleInvalidContainerIdentifier() {
        return validationProblem(1);
    }

    @ExceptionHandler(InvalidContainerLogTailException.class)
    ProblemDetail handleInvalidContainerLogTail() {
        return validationProblem(1);
    }

    @ExceptionHandler({
            ContainerLogCapabilityUnavailableException.class,
            ContainerLogRetrievalUnavailableException.class
    })
    ProblemDetail handleContainerLogUnavailable() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Container log service is unavailable");
        detail.setType(URI.create("urn:homeops:problem:container-logs-unavailable"));
        detail.setTitle("Container logs unavailable");
        return detail;
    }

    @ExceptionHandler(ContainerLogBrokerCapacityException.class)
    ProblemDetail handleContainerLogCapacity() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Container log request capacity is busy");
        detail.setType(URI.create("urn:homeops:problem:container-log-capacity"));
        detail.setTitle("Container log request capacity reached");
        return detail;
    }

    @ExceptionHandler(ContainerLogRequestTimeoutException.class)
    ProblemDetail handleContainerLogTimeout() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.GATEWAY_TIMEOUT,
                "Container log request timed out");
        detail.setType(URI.create("urn:homeops:problem:container-log-timeout"));
        detail.setTitle("Container log request timed out");
        return detail;
    }

    @ExceptionHandler({
            ContainerLogsNotAllowedException.class,
            ContainerLogResultRejectedException.class
    })
    ProblemDetail handleContainerLogRejected() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Container log request cannot be processed");
        detail.setType(URI.create("urn:homeops:problem:container-logs-rejected"));
        detail.setTitle("Container logs rejected");
        return detail;
    }

    @ExceptionHandler(ContainerLogRequestConflictException.class)
    ProblemDetail handleContainerLogConflict() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A container log request is already active");
        detail.setType(URI.create("urn:homeops:problem:container-log-conflict"));
        detail.setTitle("Container log request conflicts with active work");
        return detail;
    }

    @ExceptionHandler(ContainerLogRequestGoneException.class)
    ProblemDetail handleContainerLogRequestGone() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE,
                "Container log request is no longer available");
        detail.setType(URI.create("urn:homeops:problem:container-log-request-gone"));
        detail.setTitle("Container log request expired");
        return detail;
    }

    @ExceptionHandler(ContainerControlRequestGoneException.class)
    ProblemDetail handleContainerControlRequestGone() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE,
                "Container control request is no longer available");
        detail.setType(URI.create("urn:homeops:problem:container-control-request-gone"));
        detail.setTitle("Container control request expired");
        return detail;
    }

    @ExceptionHandler(ContainerControlResultRejectedException.class)
    ProblemDetail handleContainerControlResultRejected() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Container control result cannot be processed");
        detail.setType(URI.create("urn:homeops:problem:container-control-result-rejected"));
        detail.setTitle("Container control result rejected");
        return detail;
    }

    @ExceptionHandler(ContainerNotFoundException.class)
    ProblemDetail handleContainerNotFound() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The container is not present in the latest reported snapshot");
        detail.setType(URI.create("urn:homeops:problem:container-not-found"));
        detail.setTitle("Container not found");
        return detail;
    }

    @ExceptionHandler(AmbiguousContainerIdentifierException.class)
    ProblemDetail handleAmbiguousContainerIdentifier() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The identifier matches more than one reported container");
        detail.setType(URI.create("urn:homeops:problem:container-identifier-ambiguous"));
        detail.setTitle("Container identifier is ambiguous");
        return detail;
    }

    @ExceptionHandler(ContainerInventoryUnavailableException.class)
    ProblemDetail handleContainerInventoryUnavailable() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "No Agent container snapshot is available");
        detail.setType(URI.create("urn:homeops:problem:container-inventory-unavailable"));
        detail.setTitle("Container inventory unavailable");
        return detail;
    }

    private static ProblemDetail validationProblem(int errorCount) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed");
        detail.setType(URI.create("urn:homeops:problem:validation"));
        detail.setTitle("Invalid request");
        detail.setProperty("errorCount", errorCount);
        return detail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableRequest() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is malformed or contains an unsupported value");
        detail.setType(URI.create("urn:homeops:problem:malformed-request"));
        detail.setTitle("Malformed request");
        return detail;
    }

    @ExceptionHandler({
            EventKeyConflictException.class,
            InvalidIngestionStateTransitionException.class,
            SignalIngestionConflictException.class
    })
    ProblemDetail handleIngestionConflict(RuntimeException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:ingestion-conflict"));
        detail.setTitle("Ingestion request conflicts with existing state");
        return detail;
    }

    @ExceptionHandler(DuplicateMonitoredServiceNameException.class)
    ProblemDetail handleDuplicateMonitoredServiceName(DuplicateMonitoredServiceNameException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:duplicate-service-name"));
        detail.setTitle("Service name already exists");
        return detail;
    }

    @ExceptionHandler(MonitoredServiceNotFoundException.class)
    ProblemDetail handleMonitoredServiceNotFound() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The monitored service does not exist");
        detail.setType(URI.create("urn:homeops:problem:monitored-service-not-found"));
        detail.setTitle("Monitored service not found");
        return detail;
    }

    @ExceptionHandler(UnsafeServiceUrlException.class)
    ProblemDetail handleUnsafeServiceUrl(UnsafeServiceUrlException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:unsafe-service-url"));
        detail.setTitle("Unsafe service URL");
        return detail;
    }

    @ExceptionHandler(InvalidActivityCursorException.class)
    ProblemDetail handleInvalidActivityCursor(InvalidActivityCursorException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:invalid-activity-cursor"));
        detail.setTitle("Invalid activity cursor");
        return detail;
    }

    @ExceptionHandler(InvalidActivityTypeException.class)
    ProblemDetail handleInvalidActivityType(InvalidActivityTypeException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:invalid-activity-type"));
        detail.setTitle("Invalid activity type");
        return detail;
    }
}
