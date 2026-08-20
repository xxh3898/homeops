package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.agent.api.AgentSnapshotRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerNotificationIdentityTest {
    private static final String FULL_ID = "0123456789abcdef".repeat(4);

    @Test
    void should_createDeterministicBoundedHashes_withoutRetainingDockerId() {
        var first = ContainerNotificationIdentity.from(container(FULL_ID, "api", "project"));
        var repeated = ContainerNotificationIdentity.from(container(FULL_ID, "api", "project"));
        var recreated = ContainerNotificationIdentity.from(
                container("fedcba9876543210".repeat(4), "api", "project"));

        assertThat(first).isEqualTo(repeated);
        assertThat(first.logicalHash()).matches("[0-9a-f]{64}");
        assertThat(first.instanceFingerprint()).matches("[0-9a-f]{64}");
        assertThat(first.logicalHash()).isEqualTo(recreated.logicalHash());
        assertThat(first.instanceFingerprint()).isNotEqualTo(recreated.instanceFingerprint());
        assertThat(first.logicalHash()).doesNotContain(FULL_ID);
        assertThat(first.instanceFingerprint()).doesNotContain(FULL_ID);
    }

    @Test
    void should_distinguishLengthPrefixedLogicalIdentityComponents() {
        var first = ContainerNotificationIdentity.from(container(FULL_ID, "c", "ab"));
        var second = ContainerNotificationIdentity.from(container(FULL_ID, "bc", "a"));

        assertThat(first.logicalHash()).isNotEqualTo(second.logicalHash());
    }

    @Test
    void should_canonicalizeBlankProjectToStandaloneIdentity() {
        var blank = ContainerNotificationIdentity.from(container(FULL_ID, "api", " "));
        var absent = ContainerNotificationIdentity.from(container(FULL_ID, "api", null));

        assertThat(blank.logicalHash()).isEqualTo(absent.logicalHash());
        assertThat(blank.composeProject()).isNull();
    }

    private static AgentSnapshotRequest.ContainerSnapshot container(
            String id,
            String name,
            String project) {
        return new AgentSnapshotRequest.ContainerSnapshot(
                id,
                name,
                project,
                "private.example.invalid/image:tag",
                AgentSnapshotRequest.ContainerState.RUNNING,
                AgentSnapshotRequest.ContainerHealth.HEALTHY,
                "raw status",
                Instant.parse("2026-08-20T00:00:00Z"),
                0,
                null,
                null,
                null,
                List.of(),
                false,
                false,
                true);
    }
}
