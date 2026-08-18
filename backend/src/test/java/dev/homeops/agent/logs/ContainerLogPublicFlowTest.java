package dev.homeops.agent.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.logs.api.AgentLogResultRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContainerLogPublicFlowTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private AgentSnapshotService snapshotService;

    @Test
    void should_completeSynchronousRead_when_existingAgentBrokerReturnsResult()
            throws Exception {
        when(snapshotService.authorizeContainerLogs(anyString()))
                .thenAnswer(invocation -> new ContainerLogEligibility(
                        invocation.getArgument(0)));
        AtomicInteger identifiers = new AtomicInteger();
        ContainerLogBroker broker = new ContainerLogBroker(
                snapshotService,
                new ContainerLogRedactor(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString(
                        "10000000-0000-4000-8000-%012d".formatted(
                                identifiers.incrementAndGet())),
                Duration.ofSeconds(1));
        ContainerLogQueryService service = new ContainerLogQueryService(
                broker, Clock.fixed(NOW, ZoneOffset.UTC));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<ContainerLogResult> publicResponse =
                    CompletableFuture.supplyAsync(
                            () -> service.read("0123456789ab", 50), executor);
            ContainerLogWork work = broker.claimNext().orElseThrow();
            broker.complete(new AgentLogResultRequest(
                    work.requestId(),
                    ContainerLogResultStatus.SUCCESS,
                    NOW,
                    List.of(new AgentLogResultRequest.Line(
                            NOW,
                            ContainerLogStream.STDERR,
                            "token=synthetic-value")),
                    false,
                    false));

            ContainerLogResult result = publicResponse.get(1, TimeUnit.SECONDS);

            assertThat(result.lines()).extracting(ContainerLogLine::message)
                    .containsExactly("token=[REDACTED]");
            assertThat(result.redactionApplied()).isTrue();
            assertThat(broker.activeRequestCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_removeBrokerEntry_when_publicDeadlineIsAlreadyReached() {
        when(snapshotService.authorizeContainerLogs(anyString()))
                .thenAnswer(invocation -> new ContainerLogEligibility(
                        invocation.getArgument(0)));
        ContainerLogBroker broker = new ContainerLogBroker(
                snapshotService,
                new ContainerLogRedactor(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString(
                        "10000000-0000-4000-8000-000000000001"),
                Duration.ZERO);
        ContainerLogQueryService service = new ContainerLogQueryService(
                broker,
                Clock.fixed(NOW.plusSeconds(6), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.read("0123456789ab", 100))
                .isInstanceOf(ContainerLogRequestTimeoutException.class);
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.tombstoneCount()).isEqualTo(1);
    }
}
