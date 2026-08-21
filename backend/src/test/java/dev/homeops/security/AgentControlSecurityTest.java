package dev.homeops.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.control.ContainerControlBroker;
import dev.homeops.agent.control.ContainerControlOperation;
import dev.homeops.agent.control.ContainerControlWork;
import dev.homeops.agent.control.api.AgentControlController;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AgentControlController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class AgentControlSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContainerControlBroker broker;

    @Test
    void should_allowOnlyVerifiedAgentAndDisableCaching_when_pollingWork() throws Exception {
        when(broker.claimNext()).thenReturn(Optional.of(new ContainerControlWork(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                "0123456789ab",
                "example",
                ContainerControlOperation.START,
                Instant.parse("2026-08-20T12:00:15Z"))));

        mockMvc.perform(get("/api/v1/internal/agent/control-requests/next")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_allowVerifiedAgentResultWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/control-results")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void should_rejectBrowserAndUnverifiedAgent_fromInternalControlRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/internal/agent/control-requests/next")
                        .header("Tailscale-User-Login", "admin@example.test"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/internal/agent/control-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResult()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(broker);
    }

    @Test
    void should_rejectWrongMethod_onExactControlRoutes() throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/control-requests/next")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/internal/agent/control-results")
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isMethodNotAllowed());
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
