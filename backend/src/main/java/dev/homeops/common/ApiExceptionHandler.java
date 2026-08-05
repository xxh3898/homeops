package dev.homeops.common;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed");
        detail.setType(URI.create("urn:homeops:problem:validation"));
        detail.setTitle("Invalid request");
        detail.setProperty("errorCount", exception.getErrorCount());
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

    @ExceptionHandler({EventKeyConflictException.class, InvalidIngestionStateTransitionException.class})
    ProblemDetail handleIngestionConflict(RuntimeException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setType(URI.create("urn:homeops:problem:ingestion-conflict"));
        detail.setTitle("Ingestion request conflicts with existing state");
        return detail;
    }
}
