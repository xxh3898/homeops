package dev.homeops.agent.control.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.control.ContainerControlBroker;
import dev.homeops.agent.control.ContainerControlOperation;
import dev.homeops.agent.control.ContainerControlReasonCode;
import dev.homeops.agent.control.ContainerControlRequestGoneException;
import dev.homeops.agent.control.ContainerControlResultStatus;
import dev.homeops.agent.control.ContainerControlWork;
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
class AgentControlControllerTest {

    @Mock private ContainerControlBroker broker;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentControlController(broker))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnStrictBoundedWork_when_agentPollClaimsRequest() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.of(new ContainerControlWork(
                requestId(),
                "0123456789ab",
                "example",
                ContainerControlOperation.RESTART,
                Instant.parse("2026-08-20T12:00:15Z"))));

        mockMvc.perform(get("/api/v1/internal/agent/control-requests/next"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(requestId().toString()))
                .andExpect(jsonPath("$.containerId").value("0123456789ab"))
                .andExpect(jsonPath("$.composeProject").value("example"))
                .andExpect(jsonPath("$.operation").value("RESTART"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-20T12:00:15Z"))
                .andExpect(jsonPath("$.command").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void should_returnNoContent_when_noControlWorkExists() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/agent/control-requests/next"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_acceptStrictResultAndRejectUnknownField() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/control-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent());
        verify(broker).complete(new AgentControlResultRequest(
                requestId(),
                ContainerControlResultStatus.APPLIED,
                ContainerControlReasonCode.APPLIED,
                Instant.parse("2026-08-20T12:00:01Z")));

        mockMvc.perform(post("/api/v1/internal/agent/control-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult().replace(
                                "\"finishedAt\"",
                                "\"fullContainerId\":\"forbidden\",\"finishedAt\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_returnGoneWithoutMetadata_when_resultRequestIsUnknown() throws Exception {
        org.mockito.Mockito.doThrow(new ContainerControlRequestGoneException())
                .when(broker).complete(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/api/v1/internal/agent/control-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail")
                        .value("Container control request is no longer available"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("0123456789ab"))));
    }

    private static UUID requestId() {
        return UUID.fromString("10000000-0000-4000-8000-000000000001");
    }

    private static String validResult() {
        return """
                {
                  "requestId":"10000000-0000-4000-8000-000000000001",
                  "status":"APPLIED",
                  "reasonCode":"APPLIED",
                  "finishedAt":"2026-08-20T12:00:01Z"
                }
                """;
    }
}
