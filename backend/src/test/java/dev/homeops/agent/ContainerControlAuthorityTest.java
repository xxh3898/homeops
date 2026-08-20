package dev.homeops.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.homeops.agent.ContainerControlAuthority.DecisionCode;
import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.agent.api.AgentSnapshotRequest.HostSnapshot;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.config.HomeOpsControlProperties;
import dev.homeops.agent.domain.ReceivedAgentSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerControlAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String SHORT_ID = "0123456789ab";
    private static final String FULL_ID = SHORT_ID + "cdef0123456789abcdef";

    private AgentSnapshotService snapshots;

    @BeforeEach
    void setUp() {
        snapshots = mock(AgentSnapshotService.class);
    }

    @Test
    void should_returnBoundedTarget_when_candidateMeetsEveryAuthorityCondition() {
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "example", true)))));

        var decision = authority("example").evaluate(SHORT_ID);

        assertThat(decision.code()).isEqualTo(DecisionCode.ELIGIBLE);
        assertThat(decision.eligible()).isTrue();
        assertThat(decision.target()).isNotNull();
        assertThat(decision.target().containerId()).isEqualTo(SHORT_ID);
        assertThat(decision.target().composeProject()).isEqualTo("example");
        assertThat(decision.toString())
                .doesNotContain(FULL_ID)
                .doesNotContain("example/image")
                .doesNotContain("composeProject=example");
    }

    @Test
    void should_failClosed_when_allowlistIsEmptyOrProjectIsNotAllowed() {
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "example", true)))));

        assertThat(authority("").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_NOT_ALLOWED);
        assertThat(authority("other").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_NOT_ALLOWED);
    }

    @Test
    void should_denyProtectedAndUnavailableProjects_evenWhen_allowlisted() {
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "homeops", true)))));
        assertThat(authority("homeops").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROTECTED_PROJECT);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "", true)))));
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_UNAVAILABLE);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, " ", true)))));
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_UNAVAILABLE);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, null, true)))));
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_UNAVAILABLE);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "unknown", true)))));
        assertThat(authority("unknown").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.PROJECT_UNAVAILABLE);
    }

    @Test
    void should_denyCandidate_when_containerIsNotManaged() {
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "example", false)))));

        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.NOT_MANAGED);
    }

    @Test
    void should_denyCandidate_when_snapshotIsMissingOrStale() {
        when(snapshots.latest()).thenReturn(Optional.empty());
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.SNAPSHOT_UNAVAILABLE);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(31), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "example", true)))));
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.STALE_SNAPSHOT);

        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(31),
                List.of(container(FULL_ID, "example", true)))));
        assertThat(authority("example").evaluate(SHORT_ID).code())
                .isEqualTo(DecisionCode.STALE_SNAPSHOT);
    }

    @Test
    void should_failClosed_when_identifierHasZeroOrMultipleMatches() {
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(container(FULL_ID, "example", true)))));
        assertThat(authority("example").evaluate("ffffffffffff").code())
                .isEqualTo(DecisionCode.CONTAINER_NOT_FOUND);

        String first = "aaaaaaaaaaaa11111111111111111111";
        String second = "aaaaaaaaaaaa22222222222222222222";
        when(snapshots.latest()).thenReturn(Optional.of(received(
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                List.of(
                        container(first, "example", true),
                        container(second, "example", true)))));
        var ambiguous = authority("example").evaluate("aaaaaaaaaaaa");
        assertThat(ambiguous.code()).isEqualTo(DecisionCode.AMBIGUOUS_IDENTIFIER);
        assertThat(ambiguous.toString()).doesNotContain(first).doesNotContain(second);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "0123456789a",
        "0123456789abc",
        "0123456789AB",
        "0123456789ag",
        "../forbidden"
    })
    void should_returnStableDenial_when_identifierIsInvalid(String identifier) {
        var decision = authority("example").evaluate(identifier);

        assertThat(decision.code()).isEqualTo(DecisionCode.INVALID_IDENTIFIER);
        assertThat(decision.eligible()).isFalse();
        assertThat(decision.target()).isNull();
        if (identifier != null && !identifier.isBlank()) {
            assertThat(decision.toString()).doesNotContain(identifier);
        }
    }

    private ContainerControlAuthority authority(String allowedProjects) {
        return new ContainerControlAuthority(
                snapshots,
                new HomeOpsAgentProperties(
                        "local-mac",
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        128,
                        Duration.ofDays(1)),
                new HomeOpsControlProperties(allowedProjects),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReceivedAgentSnapshot received(
            Instant capturedAt,
            Instant receivedAt,
            List<ContainerSnapshot> containers) {
        return new ReceivedAgentSnapshot(
                new AgentSnapshotRequest(
                        UUID.fromString("10000000-0000-4000-8000-000000000053"),
                        "local-mac",
                        "1111111111111111111111111111111111111111",
                        capturedAt,
                        false,
                        new HostSnapshot(1, 100, 50, 100, 50, 1),
                        containers),
                receivedAt);
    }

    private static ContainerSnapshot container(
            String id,
            String project,
            boolean managed) {
        return new ContainerSnapshot(
                id,
                "example-api",
                project,
                "example/image:bounded",
                ContainerState.RUNNING,
                ContainerHealth.HEALTHY,
                "Up",
                NOW.minusSeconds(60),
                0,
                null,
                null,
                null,
                List.of(),
                managed);
    }
}
