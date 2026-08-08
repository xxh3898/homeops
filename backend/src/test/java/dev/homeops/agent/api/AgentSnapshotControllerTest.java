package dev.homeops.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.common.ApiExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AgentSnapshotControllerTest {

    @Mock
    private AgentSnapshotService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentSnapshotController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnAccepted_when_snapshotIsValid() throws Exception {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000010");
        when(service.accept(any())).thenReturn(
                new AgentSnapshotAcceptedResponse(
                        snapshotId,
                        Instant.parse("2026-08-04T12:00:00Z"),
                        false));

        mockMvc.perform(post("/api/v1/internal/agent/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(snapshotId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.snapshotId")
                        .value(snapshotId.toString()))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void should_returnBadRequest_when_agentIdentifierIsInvalid()
            throws Exception {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000011");

        mockMvc.perform(post("/api/v1/internal/agent/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(snapshotId)
                                .replace("local-mac", "invalid agent")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void should_returnBadRequest_when_containerListIsNull()
            throws Exception {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000012");

        mockMvc.perform(post("/api/v1/internal/agent/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(snapshotId)
                                .replace("\"containers\": []", "\"containers\": null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void should_returnBadRequestWithoutServiceAccess_when_agentVersionContainsNul()
            throws Exception {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000013");

        mockMvc.perform(post("/api/v1/internal/agent/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(snapshotId).replace("0.1.0", "\\u0000.1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(service);
    }

    private static String validJson(UUID snapshotId) {
        return """
                {
                  "snapshotId": "%s",
                  "agentId": "local-mac",
                  "agentVersion": "0.1.0",
                  "capturedAt": "2026-08-04T11:59:58Z",
                  "host": {
                    "cpuUsagePercent": 12.5,
                    "memoryTotalBytes": 16000,
                    "memoryUsedBytes": 8000,
                    "diskTotalBytes": 100000,
                    "diskUsedBytes": 40000,
                    "uptimeSeconds": 7200
                  },
                  "containers": []
                }
                """.formatted(snapshotId);
    }
}
