package dev.homeops.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.logs.ContainerLogQueryService;
import dev.homeops.agent.logs.ContainerLogResult;
import dev.homeops.agent.logs.ContainerLogResultStatus;
import dev.homeops.system.api.ContainerLogController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ContainerLogController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class ContainerLogSecurityTest {

    private static final String CONTAINER_ID = "0123456789ab";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ContainerLogQueryService queryService;

    @Test
    void should_allowAdminAndDisableCaching_when_identityIsAllowlisted()
            throws Exception {
        when(queryService.read(CONTAINER_ID, 100)).thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER,
                                "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void should_rejectAnonymousWithoutDispatching_when_identityIsMissing()
            throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void should_rejectUnlistedIdentityWithoutDispatching_when_identityIsNotAllowed()
            throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER,
                                "reader@example.test"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    @Test
    void should_ignoreAgentVerificationHeader_when_requestUsesPublicRoute()
            throws Exception {
        mockMvc.perform(get("/api/v1/containers/{id}/logs", CONTAINER_ID)
                        .header(AgentProxyAuthenticationFilter.VERIFIED_HEADER, "SUCCESS"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }

    private static ContainerLogResult emptyResult() {
        return new ContainerLogResult(
                ContainerLogResultStatus.SUCCESS,
                List.of(),
                false,
                Instant.parse("2026-08-18T00:00:00Z"),
                false);
    }
}
