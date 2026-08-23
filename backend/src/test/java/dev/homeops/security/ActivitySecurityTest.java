package dev.homeops.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.activity.ActivityService;
import dev.homeops.activity.ActivityTypeFilter;
import dev.homeops.activity.api.ActivityController;
import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import dev.homeops.activity.api.ActivityPageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ActivityController.class, properties = {
        "homeops.security.mode=TAILSCALE",
        "homeops.security.allowed-users=admin@example.test"
})
@Import(SecurityConfig.class)
class ActivitySecurityTest {
    private static final Instant NOW = Instant.parse("2026-08-23T01:00:00Z");
    private static final String OPERATION_ID = "10000000-0000-0000-0000-000000000073";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ActivityService service;

    @Test
    void should_returnBoundedContainerActionAndDisableCaching_when_adminIsAllowlisted() throws Exception {
        when(service.page(null, ActivityTypeFilter.ALL, 25)).thenReturn(response());

        mockMvc.perform(get("/api/v1/activity")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.items[0].id").value(OPERATION_ID))
                .andExpect(jsonPath("$.items[0].type").value("CONTAINER_ACTION"))
                .andExpect(jsonPath("$.items[0].title").value("Container restart"))
                .andExpect(jsonPath("$.items[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.items[0].severity").value("INFO"))
                .andExpect(jsonPath("$.items[0].context").value("0123456789ab"))
                .andExpect(jsonPath("$.items[0].principal").doesNotExist())
                .andExpect(jsonPath("$.items[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].containerName").doesNotExist())
                .andExpect(jsonPath("$.items[0].image").doesNotExist())
                .andExpect(jsonPath("$.items[0].failureSummary").doesNotExist())
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist());
    }

    @Test
    void should_preserveAdminAndNoStoreBoundary_when_activityIsFiltered() throws Exception {
        when(service.page(null, ActivityTypeFilter.CONTAINER_ACTION, 25)).thenReturn(response());

        mockMvc.perform(get("/api/v1/activity")
                        .param("type", "CONTAINER_ACTION")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "admin@example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.items[0].type").value("CONTAINER_ACTION"));
    }

    @Test
    void should_rejectActivity_when_identityIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void should_rejectActivity_when_identityIsNotAllowlisted() throws Exception {
        mockMvc.perform(get("/api/v1/activity")
                        .header(TailscaleIdentityFilter.IDENTITY_HEADER, "reader@example.test"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    private static ActivityPageResponse response() {
        return new ActivityPageResponse(List.of(new ActivityEventResponse(
                OPERATION_ID,
                Type.CONTAINER_ACTION,
                "Container restart",
                "APPLIED",
                Severity.INFO,
                NOW,
                "0123456789ab")), null, NOW);
    }
}
