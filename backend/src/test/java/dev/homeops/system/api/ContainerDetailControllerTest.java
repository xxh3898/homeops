package dev.homeops.system.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.metrics.MetricHistoryService;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ContainerDetailControllerTest {

    private static final String SHORT_ID = "0123456789ab";
    private static final String FULL_ID =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock private AgentSnapshotService agentSnapshotService;
    @Mock private MetricHistoryService metricHistoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SystemController(agentSnapshotService, metricHistoryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnAllowlistedContainerDetail_when_identifierHasSingleMatch() throws Exception {
        when(agentSnapshotService.containerDetail(SHORT_ID)).thenReturn(response());

        mockMvc.perform(get("/api/v1/containers/{id}", SHORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.lastUpdatedAt").value("2026-08-04T12:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.supportsContainerLogs").value(true))
                .andExpect(jsonPath("$.container.id").value(SHORT_ID))
                .andExpect(jsonPath("$.container.name").value("example-api"))
                .andExpect(jsonPath("$.container.health").value("HEALTHY"))
                .andExpect(jsonPath("$.container.logsAllowed").value(true))
                .andExpect(jsonPath("$.container.notificationsAllowed").doesNotExist())
                .andExpect(content().string(not(containsString(FULL_ID))));
    }

    @Test
    void should_notExposeNotificationCapability_when_returningContainerInventory() throws Exception {
        ContainerDetailResponse detail = response();
        when(agentSnapshotService.containerInventory()).thenReturn(new ContainerInventoryResponse(
                detail.agentStatus(),
                detail.lastUpdatedAt(),
                detail.stale(),
                List.of(detail.container())));

        mockMvc.perform(get("/api/v1/containers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containers[0].id").value(SHORT_ID))
                .andExpect(jsonPath("$.containers[0].logsAllowed").value(true))
                .andExpect(jsonPath("$.containers[0].notificationsAllowed").doesNotExist());
    }

    @Test
    void should_returnBadRequestWithoutIdentifier_when_identifierIsInvalid() throws Exception {
        when(agentSnapshotService.containerDetail("invalid-id"))
                .thenThrow(new InvalidContainerIdentifierException());

        mockMvc.perform(get("/api/v1/containers/{id}", "invalid-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    @Test
    void should_returnNotFoundWithoutCandidate_when_containerIsAbsent() throws Exception {
        when(agentSnapshotService.containerDetail(SHORT_ID))
                .thenThrow(new ContainerNotFoundException());

        mockMvc.perform(get("/api/v1/containers/{id}", SHORT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:container-not-found"))
                .andExpect(jsonPath("$.title").value("Container not found"))
                .andExpect(jsonPath("$.detail")
                        .value("The container is not present in the latest reported snapshot"))
                .andExpect(content().string(not(containsString(FULL_ID))));
    }

    @Test
    void should_returnConflictWithoutCandidates_when_identifierIsAmbiguous() throws Exception {
        when(agentSnapshotService.containerDetail(SHORT_ID))
                .thenThrow(new AmbiguousContainerIdentifierException());

        mockMvc.perform(get("/api/v1/containers/{id}", SHORT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-identifier-ambiguous"))
                .andExpect(jsonPath("$.title").value("Container identifier is ambiguous"))
                .andExpect(jsonPath("$.detail")
                        .value("The identifier matches more than one reported container"))
                .andExpect(content().string(not(containsString(FULL_ID))));
    }

    @Test
    void should_returnServiceUnavailable_when_snapshotIsUnavailable() throws Exception {
        when(agentSnapshotService.containerDetail(SHORT_ID))
                .thenThrow(new ContainerInventoryUnavailableException());

        mockMvc.perform(get("/api/v1/containers/{id}", SHORT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-inventory-unavailable"));
    }

    private static ContainerDetailResponse response() {
        return new ContainerDetailResponse(
                "CONNECTED",
                Instant.parse("2026-08-04T12:00:00Z"),
                false,
                true,
                new ContainerView(
                        SHORT_ID,
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
                        List.of(new ContainerView.PortView(8080, 13080, "TCP")),
                        false,
                        true));
    }
}
