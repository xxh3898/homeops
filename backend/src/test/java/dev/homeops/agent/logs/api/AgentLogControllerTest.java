package dev.homeops.agent.logs.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.logs.ContainerLogBroker;
import dev.homeops.agent.logs.ContainerLogRequestGoneException;
import dev.homeops.agent.logs.ContainerLogWork;
import dev.homeops.common.ApiExceptionHandler;
import java.time.Instant;
import java.util.Optional;
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
class AgentLogControllerTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");

    @Mock private ContainerLogBroker broker;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentLogController(broker))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnBoundedWork_when_requestIsPending() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.of(
                new ContainerLogWork(
                        REQUEST_ID,
                        "0123456789ab",
                        50,
                        Instant.parse("2026-08-18T00:00:06Z"))));

        mockMvc.perform(get("/api/v1/internal/agent/log-requests/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.containerId").value("0123456789ab"))
                .andExpect(jsonPath("$.tail").value(50))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-18T00:00:06Z"));
    }

    @Test
    void should_returnNoContent_when_noWorkIsPending() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/agent/log-requests/next"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_acceptStrictBoundedResult() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent());

        verify(broker).complete(any(AgentLogResultRequest.class));
    }

    @Test
    void should_rejectUnknownResultField() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult().replace(
                                "\"truncated\":false",
                                "\"truncated\":false,\"command\":\"forbidden\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_rejectResultWithoutCollectedTimestamp() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult().replace(
                                "\"collectedAt\":\"2026-08-18T00:00:00Z\",",
                                "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_rejectResultWithoutRedactionMetadata() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult().replace(
                                ",\n  \"redactionApplied\":false",
                                "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_returnGoneWithoutMetadata_when_requestIsUnknown() throws Exception {
        doThrow(new ContainerLogRequestGoneException())
                .when(broker).complete(any());

        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail")
                        .value("Container log request is no longer available"));
    }

    private static String validResult() {
        return """
                {
                  "requestId":"%s",
                  "status":"SUCCESS",
                  "collectedAt":"2026-08-18T00:00:00Z",
                  "lines":[{
                    "timestamp":"2026-08-18T00:00:00Z",
                    "stream":"STDOUT",
                    "message":"safe synthetic fixture"
                  }],
                  "truncated":false,
                  "redactionApplied":false
                }
                """.formatted(REQUEST_ID);
    }
}
