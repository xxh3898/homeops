package dev.homeops.agent.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

class OldApiNewAgentCompatibilityTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LegacySnapshotController())
                .build();
    }

    @Test
    void should_ignoreNewSnapshotCapabilities_when_legacyApiDeserializesNewAgent() throws Exception {
        mockMvc.perform(post("/legacy-agent-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "snapshotId":"10000000-0000-4000-8000-000000000001",
                                  "agentId":"local-mac",
                                  "agentVersion":"new-agent",
                                  "capturedAt":"2026-08-18T00:00:00Z",
                                  "supportsContainerLogs":true,
                                  "host":{},
                                  "containers":[{"id":"0123456789abcdef","logsAllowed":true,"notificationsAllowed":true}]
                                }
                                """))
                .andExpect(status().isAccepted());
    }

    @RestController
    static class LegacySnapshotController {

        @PostMapping("/legacy-agent-snapshots")
        @ResponseStatus(HttpStatus.ACCEPTED)
        void accept(@RequestBody LegacySnapshot request) {
            if (request.snapshotId() == null) {
                throw new IllegalArgumentException("legacy snapshot is invalid");
            }
        }
    }

    record LegacySnapshot(
            UUID snapshotId,
            String agentId,
            String agentVersion,
            Instant capturedAt) {
    }
}
