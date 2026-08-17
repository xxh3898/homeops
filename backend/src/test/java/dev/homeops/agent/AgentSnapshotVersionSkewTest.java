package dev.homeops.agent;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.homeops.agent.api.AgentSnapshotRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentSnapshotVersionSkewTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void should_defaultCapabilitiesToFalse_when_newApiReceivesOldAgentSnapshot() throws Exception {
        AgentSnapshotRequest request = mapper.readValue(oldAgentSnapshot(), AgentSnapshotRequest.class);

        assertThat(request.supportsContainerLogs()).isFalse();
        assertThat(request.containers()).hasSize(1);
        assertThat(request.containers().getFirst().logsAllowed()).isFalse();
    }

    @Test
    void should_ignoreNewCapabilities_when_oldApiReceivesNewAgentSnapshot() throws Exception {
        LegacySnapshot request = mapper.readValue(newAgentSnapshot(), LegacySnapshot.class);

        assertThat(request.snapshotId()).isEqualTo(
                UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThat(request.containers()).extracting(LegacyContainer::id)
                .containsExactly("0123456789abcdef");
    }

    private static String oldAgentSnapshot() {
        return """
                {
                  "snapshotId":"10000000-0000-4000-8000-000000000001",
                  "agentId":"local-mac",
                  "agentVersion":"old-agent",
                  "capturedAt":"2026-08-18T00:00:00Z",
                  "host":{"cpuUsagePercent":1.0,"memoryTotalBytes":100,"memoryUsedBytes":50,"diskTotalBytes":100,"diskUsedBytes":50,"uptimeSeconds":1},
                  "containers":[{"id":"0123456789abcdef","name":"api","image":"example","state":"RUNNING","health":"HEALTHY","restartCount":0,"ports":[],"managed":false}]
                }
                """;
    }

    private static String newAgentSnapshot() {
        return """
                {
                  "snapshotId":"10000000-0000-4000-8000-000000000001",
                  "agentId":"local-mac",
                  "agentVersion":"new-agent",
                  "capturedAt":"2026-08-18T00:00:00Z",
                  "supportsContainerLogs":true,
                  "host":{},
                  "containers":[{"id":"0123456789abcdef","logsAllowed":true}]
                }
                """;
    }

    private record LegacySnapshot(
            UUID snapshotId,
            String agentId,
            String agentVersion,
            Instant capturedAt,
            JsonNode host,
            List<LegacyContainer> containers) {
    }

    private record LegacyContainer(String id) {
    }
}
