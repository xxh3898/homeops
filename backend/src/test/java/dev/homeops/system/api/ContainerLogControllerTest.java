package dev.homeops.system.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.logs.ContainerLogBrokerCapacityException;
import dev.homeops.agent.logs.ContainerLogCapabilityUnavailableException;
import dev.homeops.agent.logs.ContainerLogLine;
import dev.homeops.agent.logs.ContainerLogQueryService;
import dev.homeops.agent.logs.ContainerLogRequestConflictException;
import dev.homeops.agent.logs.ContainerLogRequestTimeoutException;
import dev.homeops.agent.logs.ContainerLogResult;
import dev.homeops.agent.logs.ContainerLogResultStatus;
import dev.homeops.agent.logs.ContainerLogStream;
import dev.homeops.agent.logs.ContainerLogsNotAllowedException;
import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ContainerLogControllerTest {

    private static final String CONTAINER_ID = "0123456789ab";
    private static final String FULL_ID =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant COLLECTED_AT =
            Instant.parse("2026-08-18T00:00:00Z");

    @Mock private ContainerLogQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ContainerLogController(queryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_useDefaultTailAndReturnAllowlistedPayload_when_tailIsMissing()
            throws Exception {
        when(queryService.read(CONTAINER_ID, 100)).thenReturn(successResult());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerId").value(CONTAINER_ID))
                .andExpect(jsonPath("$.requestedTail").value(100))
                .andExpect(jsonPath("$.collectedAt").value(COLLECTED_AT.toString()))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.redactionApplied").value(true))
                .andExpect(jsonPath("$.lines[0].timestamp")
                        .value(COLLECTED_AT.toString()))
                .andExpect(jsonPath("$.lines[0].stream").value("STDOUT"))
                .andExpect(jsonPath("$.lines[0].message").value("safe line"))
                .andExpect(content().string(not(containsString("requestId"))))
                .andExpect(content().string(not(containsString(FULL_ID))))
                .andExpect(content().string(not(containsString("containerName"))))
                .andExpect(content().string(not(containsString("composeProject"))))
                .andExpect(content().string(not(containsString("rawError"))))
                .andExpect(content().string(not(containsString("labels"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 100, 200})
    void should_acceptExactBoundedTail_when_tailIsSupported(int tail)
            throws Exception {
        when(queryService.read(CONTAINER_ID, tail)).thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .queryParam("tail", Integer.toString(tail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedTail").value(tail))
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", "0", "-1", "1", "500", "all", "not-a-number", "050", "+50", "100.0"
            })
    void should_returnBadRequest_when_tailIsOutsideAllowlist(String tail)
            throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .queryParam("tail", tail))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
    }

    @Test
    void should_returnBadRequest_when_tailIsDuplicated() throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .queryParam("tail", "50", "100"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"since", "until", "follow", "query", "filter", "regex"})
    void should_returnBadRequest_when_unsupportedQueryIsPresent(String query)
            throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .queryParam("tail", "100")
                        .queryParam(query, "unsupported"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_returnBadRequest_when_identifierIsInvalid() throws Exception {
        when(queryService.read("invalid-id", 100))
                .thenThrow(new InvalidContainerIdentifierException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", "invalid-id"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_returnNotFound_when_containerIsAbsent() throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerNotFoundException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_returnConflict_when_identifierIsAmbiguous() throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new AmbiguousContainerIdentifierException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isConflict());
    }

    @Test
    void should_returnUnprocessable_when_logsAreNotEnabled() throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerLogsNotAllowedException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void should_returnConflict_when_sameContainerRequestIsAlreadyActive()
            throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerLogRequestConflictException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isConflict());
    }

    @Test
    void should_returnTooManyRequests_when_brokerCapacityIsFull() throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerLogBrokerCapacityException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-log-capacity"));
    }

    @Test
    void should_returnServiceUnavailable_when_capabilityIsUnavailable()
            throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerLogCapabilityUnavailableException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void should_returnGatewayTimeout_when_brokerDeadlineExpires() throws Exception {
        when(queryService.read(CONTAINER_ID, 100))
                .thenThrow(new ContainerLogRequestTimeoutException());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:container-log-timeout"));
    }

    private static ContainerLogResult successResult() {
        return new ContainerLogResult(
                ContainerLogResultStatus.SUCCESS,
                List.of(new ContainerLogLine(
                        COLLECTED_AT,
                        ContainerLogStream.STDOUT,
                        "safe line")),
                true,
                COLLECTED_AT,
                true);
    }

    private static ContainerLogResult emptyResult() {
        return new ContainerLogResult(
                ContainerLogResultStatus.SUCCESS,
                List.of(),
                false,
                COLLECTED_AT,
                false);
    }
}
