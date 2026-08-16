package dev.homeops.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.metrics.MetricHistoryService;
import dev.homeops.system.api.MetricHistoryResponse;
import dev.homeops.system.api.SystemController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SystemController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class MetricHistorySecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private AgentSnapshotService agentSnapshotService;
    @MockitoBean private MetricHistoryService metricHistoryService;

    @Test
    void should_allowAdminAndDisableCaching_when_identityIsAllowlisted() throws Exception {
        when(metricHistoryService.history("1h")).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/system/metrics/history")
                        .param("period", "1h")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_rejectRequest_when_identityIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/system/metrics/history").param("period", "1h"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_rejectRequest_when_identityIsNotAllowlisted() throws Exception {
        mockMvc.perform(get("/api/v1/system/metrics/history")
                        .param("period", "1h")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "reader@example.test"))
                .andExpect(status().isUnauthorized());
    }

    private static MetricHistoryResponse emptyResponse() {
        return new MetricHistoryResponse(
                "1h",
                Instant.parse("2026-08-17T11:00:00Z"),
                Instant.parse("2026-08-17T12:00:00Z"),
                60,
                List.of());
    }
}
