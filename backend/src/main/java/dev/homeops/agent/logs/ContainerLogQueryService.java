package dev.homeops.agent.logs;

import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContainerLogQueryService {

    private final ContainerLogBroker broker;
    private final Clock clock;

    @Autowired
    public ContainerLogQueryService(ContainerLogBroker broker) {
        this(broker, Clock.systemUTC());
    }

    ContainerLogQueryService(ContainerLogBroker broker, Clock clock) {
        this.broker = broker;
        this.clock = clock;
    }

    public ContainerLogResult read(String containerId, int tail) {
        ContainerLogRequestTicket ticket = broker.request(containerId, tail);
        boolean resultReceived = false;
        try {
            Duration remaining = Duration.between(clock.instant(), ticket.expiresAt());
            if (remaining.isZero() || remaining.isNegative()) {
                throw new ContainerLogRequestTimeoutException();
            }
            if (remaining.compareTo(ContainerLogBroker.REQUEST_TTL) > 0) {
                remaining = ContainerLogBroker.REQUEST_TTL;
            }
            ContainerLogResult result = ticket.result()
                    .toCompletableFuture()
                    .get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            resultReceived = true;
            return mapResult(result);
        } catch (TimeoutException exception) {
            throw new ContainerLogRequestTimeoutException();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ContainerLogRetrievalUnavailableException();
        } catch (ExecutionException exception) {
            throw mapFailure(exception.getCause());
        } finally {
            if (!resultReceived) {
                broker.cancel(ticket);
            }
        }
    }

    private static ContainerLogResult mapResult(ContainerLogResult result) {
        return switch (result.status()) {
            case SUCCESS -> result;
            case NOT_FOUND -> throw new ContainerNotFoundException();
            case AMBIGUOUS -> throw new AmbiguousContainerIdentifierException();
            case NOT_ALLOWED -> throw new ContainerLogsNotAllowedException();
            case UNAVAILABLE, INVALID_REQUEST ->
                    throw new ContainerLogRetrievalUnavailableException();
        };
    }

    private static RuntimeException mapFailure(Throwable cause) {
        if (cause instanceof ContainerLogRequestExpiredException) {
            return new ContainerLogRequestTimeoutException();
        }
        if (cause instanceof InvalidContainerIdentifierException exception) {
            return exception;
        }
        if (cause instanceof ContainerNotFoundException exception) {
            return exception;
        }
        if (cause instanceof AmbiguousContainerIdentifierException exception) {
            return exception;
        }
        if (cause instanceof ContainerLogsNotAllowedException exception) {
            return exception;
        }
        if (cause instanceof ContainerLogCapabilityUnavailableException exception) {
            return exception;
        }
        if (cause instanceof ContainerInventoryUnavailableException exception) {
            return exception;
        }
        return new ContainerLogRetrievalUnavailableException();
    }
}
