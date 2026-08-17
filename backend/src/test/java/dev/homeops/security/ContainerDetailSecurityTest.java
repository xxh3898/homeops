package dev.homeops.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.metrics.MetricHistoryService;
import dev.homeops.system.api.ContainerDetailResponse;
import dev.homeops.system.api.ContainerView;
import dev.homeops.system.api.SystemController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ContainerDetailSecurityTest {

    private static final String CONTAINER_ID = "0123456789ab";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AgentSnapshotService agentSnapshotService;
    @MockitoBean private MetricHistoryService metricHistoryService;

    @Test
    void should_allowAdminAndDisableCaching_when_identityIsAllowlisted() throws Exception {
        when(agentSnapshotService.containerDetail(CONTAINER_ID)).thenReturn(response());

        mockMvc.perform(get("/api/v1/containers/{id}", CONTAINER_ID)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_rejectContainerDetail_when_identityIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}", CONTAINER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_rejectContainerDetail_when_identityIsNotAllowlisted() throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}", CONTAINER_ID)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "reader@example.test"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/containers/abc/def",
        "/api/v1/containers/../etc",
        "/api/v1/containers/%2Fetc"
    })
    void should_rejectTraversalPathWithoutLookup_when_pathEscapesIdentifierSegment(String path)
            throws Exception {
        mockMvc.perform(get(path)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(agentSnapshotService);
    }

    private static ContainerDetailResponse response() {
        return new ContainerDetailResponse(
                "CONNECTED",
                Instant.parse("2026-08-04T12:00:00Z"),
                false,
                new ContainerView(
                        CONTAINER_ID,
                        "example-api",
                        "example",
                        "example/api:sha-test",
                        "RUNNING",
                        "HEALTHY",
                        "Up 2 hours (healthy)",
                        Instant.parse("2026-08-04T10:00:00Z"),
                        0,
                        2.5,
                        256L,
                        512L,
                        List.of(),
                        false));
    }
}
