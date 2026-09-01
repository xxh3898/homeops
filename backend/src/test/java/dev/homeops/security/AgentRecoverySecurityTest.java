package dev.homeops.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.recovery.AutomaticRecoveryBroker;
import dev.homeops.recovery.api.AgentRecoveryController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AgentRecoveryController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class AgentRecoverySecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private AutomaticRecoveryBroker broker;

    @Test
    void should_allowOnlyVerifiedAgentAndDisableCaching_when_pollingRecoveryWork()
            throws Exception {
        mockMvc.perform(get("/api/v1/internal/agent/recovery-requests/next")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_allowRecoveryResultWithoutCsrfToken_when_agentIsVerified() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/recovery-results")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void should_rejectInternalRecoveryRoutes_when_requestIsNotVerifiedAgent() throws Exception {
        mockMvc.perform(get("/api/v1/internal/agent/recovery-requests/next")
                        .header("Tailscale-User-Login", "admin@example.test"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/internal/agent/recovery-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(broker);
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
