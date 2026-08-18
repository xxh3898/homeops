package dev.homeops.agent.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContainerLogQueryServiceTest {

    private static final String CONTAINER_ID = "0123456789ab";
    private static final UUID REQUEST_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private ContainerLogBroker broker;
    private ContainerLogQueryService service;

    @BeforeEach
    void setUp() {
        service = new ContainerLogQueryService(
                broker,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void should_returnSanitizedResult_when_brokerCompletesBeforeDeadline() {
        ContainerLogRequestTicket ticket = completedTicket(result(
                ContainerLogResultStatus.SUCCESS,
                List.of(new ContainerLogLine(
                        NOW, ContainerLogStream.STDOUT, "safe"))));
        when(broker.request(CONTAINER_ID, 100)).thenReturn(ticket);

        ContainerLogResult result = service.read(CONTAINER_ID, 100);

        assertThat(result.lines()).extracting(ContainerLogLine::message)
                .containsExactly("safe");
        verify(broker, never()).cancel(ticket);
    }

    @Test
    void should_mapAgentNotFoundToPublicNotFound_when_resultIsTerminal() {
        when(broker.request(CONTAINER_ID, 100)).thenReturn(completedTicket(result(
                ContainerLogResultStatus.NOT_FOUND, List.of())));

        assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                .isInstanceOf(ContainerNotFoundException.class);
    }

    @Test
    void should_mapAgentAmbiguityToConflict_when_resultIsTerminal() {
        when(broker.request(CONTAINER_ID, 100)).thenReturn(completedTicket(result(
                ContainerLogResultStatus.AMBIGUOUS, List.of())));

        assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                .isInstanceOf(AmbiguousContainerIdentifierException.class);
    }

    @Test
    void should_mapAgentDenialToUnprocessable_when_resultIsTerminal() {
        when(broker.request(CONTAINER_ID, 100)).thenReturn(completedTicket(result(
                ContainerLogResultStatus.NOT_ALLOWED, List.of())));

        assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                .isInstanceOf(ContainerLogsNotAllowedException.class);
    }

    @Test
    void should_failClosedAsUnavailable_when_agentReportsProtocolFailure() {
        for (ContainerLogResultStatus status : List.of(
                ContainerLogResultStatus.UNAVAILABLE,
                ContainerLogResultStatus.INVALID_REQUEST)) {
            when(broker.request(CONTAINER_ID, 100)).thenReturn(completedTicket(
                    result(status, List.of())));

            assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                    .isInstanceOf(ContainerLogRetrievalUnavailableException.class);
        }
    }

    @Test
    void should_timeoutAndCancel_withoutDisclosingCompletedPayloadAtExpiryBoundary() {
        CompletableFuture<ContainerLogResult> completed = CompletableFuture.completedFuture(
                result(ContainerLogResultStatus.SUCCESS, List.of()));
        ContainerLogRequestTicket ticket = new ContainerLogRequestTicket(
                REQUEST_ID, NOW, completed);
        when(broker.request(CONTAINER_ID, 100)).thenReturn(ticket);

        assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                .isInstanceOf(ContainerLogRequestTimeoutException.class);
        verify(broker).cancel(ticket);
    }

    @Test
    void should_mapBrokerExpiryToGatewayTimeout_when_cleanupWinsRace() {
        CompletableFuture<ContainerLogResult> expired = new CompletableFuture<>();
        expired.completeExceptionally(new ContainerLogRequestExpiredException());
        ContainerLogRequestTicket ticket = new ContainerLogRequestTicket(
                REQUEST_ID, NOW.plusSeconds(6), expired);
        when(broker.request(CONTAINER_ID, 100)).thenReturn(ticket);

        assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                .isInstanceOf(ContainerLogRequestTimeoutException.class);
        verify(broker).cancel(ticket);
    }

    @Test
    void should_cancelAndPreserveInterrupt_when_publicWaiterIsInterrupted() {
        ContainerLogRequestTicket ticket = new ContainerLogRequestTicket(
                REQUEST_ID,
                NOW.plusSeconds(6),
                new CompletableFuture<>());
        when(broker.request(CONTAINER_ID, 100)).thenReturn(ticket);

        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> service.read(CONTAINER_ID, 100))
                    .isInstanceOf(ContainerLogRetrievalUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(broker).cancel(ticket);
        } finally {
            Thread.interrupted();
        }
    }

    private static ContainerLogRequestTicket completedTicket(
            ContainerLogResult result) {
        return new ContainerLogRequestTicket(
                REQUEST_ID,
                NOW.plusSeconds(6),
                CompletableFuture.completedFuture(result));
    }

    private static ContainerLogResult result(
            ContainerLogResultStatus status,
            List<ContainerLogLine> lines) {
        return new ContainerLogResult(status, lines, false, NOW, false);
    }
}
