package dev.homeops.agent.control.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.control.ContainerActionAuditRecord;
import dev.homeops.agent.config.HomeOpsControlProperties;
import dev.homeops.agent.control.ContainerActionException;
import dev.homeops.agent.control.ContainerActionIdempotencyKey;
import dev.homeops.agent.control.ContainerActionService;
import dev.homeops.agent.control.ContainerActionStatus;
import dev.homeops.agent.control.ContainerControlOperation;
import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.security.ContainerControlOriginGuard;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ContainerActionControllerTest {
    private static final UUID OPERATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String IDEMPOTENCY_KEY = "2aaaaaaa-0000-4000-8000-000000000001";
    private static final String CONTAINER_ID = "0123456789ab";
    private static final String PRINCIPAL = "admin@example.test";
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T15:00:00Z");

    @Mock private ContainerActionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ContainerActionController(
                                service,
                                new ContainerControlOriginGuard(new HomeOpsControlProperties(
                                        "",
                                        "https://homeops.example.test:8443"))))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnAcceptedBoundedProjection_when_newOperationIsReserved() throws Exception {
        ContainerActionAuditRecord record = requested();
        when(service.submit(
                CONTAINER_ID,
                ContainerControlOperation.START,
                "START:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                PRINCIPAL))
                .thenReturn(new ContainerActionService.Submission(record, true));

        mockMvc.perform(validPost("""
                {"operation":"START","confirmation":"START:0123456789ab"}
                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(OPERATION_ID.toString()))
                .andExpect(jsonPath("$.containerId").value(CONTAINER_ID))
                .andExpect(jsonPath("$.operation").value("START"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.principal").doesNotExist())
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.project").doesNotExist())
                .andExpect(jsonPath("$.image").doesNotExist())
                .andExpect(jsonPath("$.metadata").doesNotExist());
    }

    @Test
    void should_returnOkWithoutRedispatch_when_exactReplayIsTerminal() throws Exception {
        ContainerActionAuditRecord record = terminal();
        when(service.submit(any(), any(), any(), any(), any()))
                .thenReturn(new ContainerActionService.Submission(record, false));

        mockMvc.perform(validPost("""
                {"operation":"START","confirmation":"START:0123456789ab"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.reasonCode").value("APPLIED"))
                .andExpect(jsonPath("$.completedAt").value("2026-08-20T15:00:01Z"));
    }

    @Test
    void should_returnKnownOperationWithoutMutation_when_polledById() throws Exception {
        when(service.find(OPERATION_ID)).thenReturn(terminal());

        mockMvc.perform(get("/api/v1/container-actions/{operationId}", OPERATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(OPERATION_ID.toString()))
                .andExpect(content().string(not(containsString(PRINCIPAL))));
    }

    @Test
    void should_rejectUnknownBodyFieldAndNonCanonicalIdempotencyKey() throws Exception {
        mockMvc.perform(validPost("""
                {"operation":"START","confirmation":"START:0123456789ab","command":"docker"}
                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(basePost("""
                        {"operation":"START","confirmation":"START:0123456789ab"}
                        """)
                        .header(ContainerActionController.IDEMPOTENCY_HEADER,
                                IDEMPOTENCY_KEY.toUpperCase()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-action-invalid"));
    }

    @Test
    void should_rejectMissingAndDuplicateIdempotencyHeaders() throws Exception {
        mockMvc.perform(basePost(validBody()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(basePost(validBody())
                        .header(ContainerActionController.IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .header(ContainerActionController.IDEMPOTENCY_HEADER,
                                "3aaaaaaa-0000-4000-8000-000000000001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_returnStableErrorWithoutPrivateFields_when_confirmationIsRejected() throws Exception {
        when(service.submit(eq(CONTAINER_ID), any(), any(), any(), eq(PRINCIPAL)))
                .thenThrow(ContainerActionException.confirmationMismatch());

        mockMvc.perform(validPost("""
                {"operation":"START","confirmation":"STOP:0123456789ab"}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-action-confirmation-mismatch"))
                .andExpect(content().string(not(containsString(PRINCIPAL))))
                .andExpect(content().string(not(containsString("STOP:0123456789ab"))));
    }

    @Test
    void should_mapBusyRateAuthorityAndUnavailableFailuresToStablePublicStatuses() throws Exception {
        when(service.submit(eq(CONTAINER_ID), any(), any(), any(), eq(PRINCIPAL)))
                .thenThrow(ContainerActionException.busy())
                .thenThrow(ContainerActionException.rateLimited())
                .thenThrow(ContainerActionException.denied())
                .thenThrow(ContainerActionException.unavailable());

        mockMvc.perform(validPost(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:container-action-busy"));
        mockMvc.perform(validPost(validBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-action-rate-limited"));
        mockMvc.perform(validPost(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:container-action-denied"));
        mockMvc.perform(validPost(validBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-action-unavailable"))
                .andExpect(content().string(not(containsString(PRINCIPAL))));
    }

    @Test
    void should_returnBadRequestForInvalidContainerAndNotFoundForUnknownOperation() throws Exception {
        when(service.submit(eq("invalid-id"), any(), any(), any(), eq(PRINCIPAL)))
                .thenThrow(new InvalidContainerIdentifierException());
        when(service.find(OPERATION_ID)).thenThrow(ContainerActionException.notFound());

        mockMvc.perform(post("/api/v1/containers/{containerId}/actions", "invalid-id")
                        .principal(() -> PRINCIPAL)
                        .header(HttpHeaders.ORIGIN, "https://homeops.example.test:8443")
                        .header(HttpHeaders.HOST, "homeops.example.test")
                        .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "https")
                        .header(ContainerActionController.IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation":"START","confirmation":"START:invalid-id"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/container-actions/{operationId}", OPERATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-action-not-found"));
    }

    private static MockHttpServletRequestBuilder validPost(String content) {
        return basePost(content)
                .header(ContainerActionController.IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY);
    }

    private static MockHttpServletRequestBuilder basePost(String content) {
        return post("/api/v1/containers/{containerId}/actions", CONTAINER_ID)
                .principal(() -> PRINCIPAL)
                .header(HttpHeaders.ORIGIN, "https://homeops.example.test:8443")
                .header(HttpHeaders.HOST, "homeops.example.test")
                .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "https")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private static String validBody() {
        return """
                {"operation":"START","confirmation":"START:0123456789ab"}
                """;
    }

    private static ContainerActionAuditRecord requested() {
        return new ContainerActionAuditRecord(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START,
                ContainerActionStatus.REQUESTED,
                null,
                REQUESTED_AT,
                null);
    }

    private static ContainerActionAuditRecord terminal() {
        return new ContainerActionAuditRecord(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START,
                ContainerActionStatus.APPLIED,
                "APPLIED",
                REQUESTED_AT,
                REQUESTED_AT.plusSeconds(1));
    }
}
