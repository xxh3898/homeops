package dev.homeops.recovery.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.recovery.AutomaticRecoveryAction;
import dev.homeops.recovery.AutomaticRecoveryBroker;
import dev.homeops.recovery.AutomaticRecoveryHealth;
import dev.homeops.recovery.AutomaticRecoveryProject;
import dev.homeops.recovery.AutomaticRecoveryReasonCode;
import dev.homeops.recovery.AutomaticRecoveryResultStatus;
import dev.homeops.recovery.AutomaticRecoveryTarget;
import dev.homeops.recovery.AutomaticRecoveryWork;
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
class AgentRecoveryControllerTest {
    private static final UUID REQUEST_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000119");

    @Mock private AutomaticRecoveryBroker broker;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentRecoveryController(broker))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void should_returnBoundedFixedWorkWithoutExecutionInputs_when_agentPolls() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.of(new AutomaticRecoveryWork(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.BACKEND,
                AutomaticRecoveryAction.RESTART,
                Instant.parse("2026-09-01T12:00:10Z"))));

        mockMvc.perform(get("/api/v1/internal/agent/recovery-requests/next"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.project").value("rhaomi"))
                .andExpect(jsonPath("$.target").value("backend"))
                .andExpect(jsonPath("$.action").value("RESTART"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-01T12:00:10Z"))
                .andExpect(jsonPath("$.command").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.arguments").doesNotExist())
                .andExpect(jsonPath("$.environment").doesNotExist());
    }

    @Test
    void should_returnNoContent_when_noRecoveryWorkExists() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/agent/recovery-requests/next"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_acceptStrictBoundedResultAndRejectUnknownField_when_agentPostsResult()
            throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/recovery-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent());
        verify(broker).complete(new AgentRecoveryResultRequest(
                REQUEST_ID,
                AutomaticRecoveryResultStatus.APPLIED,
                AutomaticRecoveryReasonCode.RECOVERY_APPLIED,
                Instant.parse("2026-09-01T12:00:01Z"),
                Instant.parse("2026-09-01T12:00:02Z"),
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.UP,
                1));

        mockMvc.perform(post("/api/v1/internal/agent/recovery-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult().replace(
                                "\"finishedAt\"",
                                "\"rawOutput\":\"forbidden\",\"finishedAt\"")))
                .andExpect(status().isBadRequest());
    }

    private static String validResult() {
        return """
                {
                  "requestId":"10000000-0000-4000-8000-000000000119",
                  "status":"APPLIED",
                  "reasonCode":"RECOVERY_APPLIED",
                  "startedAt":"2026-09-01T12:00:01Z",
                  "finishedAt":"2026-09-01T12:00:02Z",
                  "preHealth":"DOWN",
                  "postHealth":"UP",
                  "restartCount":1
                }
                """;
    }
}
