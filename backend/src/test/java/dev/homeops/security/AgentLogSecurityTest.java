package dev.homeops.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.logs.ContainerLogBroker;
import dev.homeops.agent.logs.ContainerLogWork;
import dev.homeops.agent.logs.api.AgentLogController;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AgentLogController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class AgentLogSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContainerLogBroker broker;

    @Test
    void should_allowVerifiedAgentAndDisableCaching_when_pollingWork() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.of(new ContainerLogWork(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                "0123456789ab",
                50)));

        mockMvc.perform(get("/api/v1/internal/agent/log-requests/next")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_allowVerifiedAgentResultWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void should_rejectUnverifiedAgentPoll() throws Exception {
        mockMvc.perform(get("/api/v1/internal/agent/log-requests/next"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(broker);
    }

    @Test
    void should_rejectUnverifiedAgentResult() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/log-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(broker);
    }

    private static String validResult() {
        return """
                {
                  "requestId":"10000000-0000-4000-8000-000000000001",
                  "status":"SUCCESS",
                  "lines":[],
                  "truncated":false
                }
                """;
    }
}
