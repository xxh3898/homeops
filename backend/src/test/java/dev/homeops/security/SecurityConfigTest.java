package dev.homeops.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.monitoring.api.MonitoringController;
import dev.homeops.monitoring.api.MonitoringService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MonitoringController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private MonitoringService service;

    @Test
    void should_allowServiceCreation_when_tailscaleAdminSuppliesCsrfToken() throws Exception {
        when(service.create(any())).thenReturn(new MonitoredServiceResponse(
                UUID.fromString("10000000-0000-0000-0000-000000000100"), "HomeOps",
                "https://homeops.example.invalid/health", "GET", 200, 3_000, 30, 3, 2,
                "WARNING", true, true));

        mockMvc.perform(post("/api/v1/services")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated());
    }

    @Test
    @WithAnonymousUser
    void should_rejectServiceCreation_when_requestHasNoAuthorizedIdentity() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isUnauthorized());
    }

    private static String validJson() {
        return """
                {"name":"HomeOps","url":"https://homeops.example.invalid/health","method":"GET","expectedStatus":200,"timeoutMs":3000,"intervalSeconds":30,"failureThreshold":3,"recoveryThreshold":2,"severity":"WARNING","enabled":true,"notificationEnabled":true}
                """;
    }
}
