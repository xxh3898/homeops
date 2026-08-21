package dev.homeops.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.control.ContainerActionAuditRecord;
import dev.homeops.agent.control.ContainerActionException;
import dev.homeops.agent.control.ContainerActionService;
import dev.homeops.agent.control.ContainerActionStatus;
import dev.homeops.agent.control.ContainerControlOperation;
import dev.homeops.agent.control.api.ContainerActionController;
import dev.homeops.agent.config.HomeOpsControlProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = ContainerActionController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test",
        "homeops.control.public-origin=https://homeops.example.test:8443"
})
@Import({SecurityConfig.class, ContainerControlOriginGuard.class})
@EnableConfigurationProperties(HomeOpsControlProperties.class)
class ContainerActionSecurityTest {
    private static final UUID OPERATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String IDEMPOTENCY_KEY = "20000000-0000-4000-8000-000000000001";
    private static final String CONTAINER_ID = "0123456789ab";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContainerActionService service;

    @Test
    void should_allowAdminWithCsrfAndExactOriginAndDisableCaching() throws Exception {
        ContainerActionAuditRecord record = record(ContainerActionStatus.REQUESTED);
        when(service.submit(any(), any(), any(), any(), any()))
                .thenReturn(new ContainerActionService.Submission(record, true));

        mockMvc.perform(validPost().with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_rejectWithoutCsrfBeforeControlService() throws Exception {
        mockMvc.perform(validPost())
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void should_rejectAnonymousAndNonAllowlistedIdentity() throws Exception {
        mockMvc.perform(basePost().with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(basePost()
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "reader@example.test")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void should_rejectMissingCrossOriginAndNonHttpsProxyBeforeControlService() throws Exception {
        mockMvc.perform(basePost()
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test")
                        .header(HttpHeaders.HOST, "homeops.example.test")
                        .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "https")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(basePost()
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test")
                        .header(HttpHeaders.ORIGIN, "https://other.example.test:8443")
                        .header(HttpHeaders.HOST, "homeops.example.test")
                        .header("X-Forwarded-Host", "homeops.example.test:8443")
                        .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "https")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(basePost()
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test")
                        .header(HttpHeaders.ORIGIN, "https://homeops.example.test:8443")
                        .header(HttpHeaders.HOST, "homeops.example.test")
                        .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "http")
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void should_reachConfirmationValidationAfterAdminCsrfAndCanonicalOrigin() throws Exception {
        when(service.submit(any(), any(), any(), any(), any()))
                .thenThrow(ContainerActionException.confirmationMismatch());

        mockMvc.perform(validPost()
                        .content("""
                                {"operation":"START","confirmation":"STOP:0123456789ab"}
                                """)
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void should_allowAdminPollingWithoutCsrfAndKeepNoStore() throws Exception {
        when(service.find(OPERATION_ID)).thenReturn(record(ContainerActionStatus.APPLIED));

        mockMvc.perform(get("/api/v1/container-actions/{operationId}", OPERATION_ID)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_rejectAnonymousPolling() throws Exception {
        mockMvc.perform(get("/api/v1/container-actions/{operationId}", OPERATION_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder validPost() {
        return basePost()
                .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test")
                .header(HttpHeaders.ORIGIN, "https://homeops.example.test:8443")
                .header(HttpHeaders.HOST, "homeops.example.test")
                .header(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, "https");
    }

    private static MockHttpServletRequestBuilder basePost() {
        return post("/api/v1/containers/{containerId}/actions", CONTAINER_ID)
                .header(ContainerActionController.IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"operation":"START","confirmation":"START:0123456789ab"}
                        """);
    }

    private static ContainerActionAuditRecord record(ContainerActionStatus status) {
        Instant requestedAt = Instant.parse("2026-08-20T15:00:00Z");
        return new ContainerActionAuditRecord(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                "admin@example.test",
                CONTAINER_ID,
                ContainerControlOperation.START,
                status,
                status.terminal() ? "APPLIED" : null,
                requestedAt,
                status.terminal() ? requestedAt.plusSeconds(1) : null);
    }
}
