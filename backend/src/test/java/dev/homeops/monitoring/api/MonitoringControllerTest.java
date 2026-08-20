package dev.homeops.monitoring.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.common.DuplicateMonitoredServiceNameException;
import dev.homeops.monitoring.MonitoredServiceNotFoundException;
import dev.homeops.monitoring.SafeServiceUrlPolicy.UnsafeServiceUrlException;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MonitoringControllerTest {
    @Mock private MonitoringService service;
    private MockMvc mockMvc;

    @BeforeEach void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitoringController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void should_createService_when_requestIsValid() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(service.create(any())).thenReturn(new MonitoredServiceResponse(id, "HomeOps", "https://homeops.example.invalid/health", "GET", 200, 3000, 30, 3, 2, "WARNING", true, true));

        mockMvc.perform(post("/api/v1/services").contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void should_rejectCreateRequest_when_notificationAuthorityIsOmitted() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace(",\"notificationEnabled\":true", "")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void should_createServiceWithFalseAuthority_when_requestExplicitlyDisablesNotification() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(service.create(any())).thenReturn(new MonitoredServiceResponse(id, "HomeOps",
                "https://homeops.example.invalid/health", "GET", 200, 3000, 30, 3, 2,
                "WARNING", true, false));

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace(
                                "\"notificationEnabled\":true",
                                "\"notificationEnabled\":false")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notificationEnabled").value(false));

        verify(service).create(argThat(request -> !request.notificationEnabled()));
    }

    @Test
    void should_updateNotificationAuthority_when_requestIsBooleanOnly() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(service.updateNotificationAuthority(id, true))
                .thenReturn(new MonitoredServiceNotificationResponse(id, true));

        mockMvc.perform(patch("/api/v1/services/{serviceId}/notification", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.notificationEnabled").value(true))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void should_returnNotFound_when_notificationServiceDoesNotExist() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000404");
        when(service.updateNotificationAuthority(id, true))
                .thenThrow(new MonitoredServiceNotFoundException());

        mockMvc.perform(patch("/api/v1/services/{serviceId}/notification", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("urn:homeops:problem:monitored-service-not-found"))
                .andExpect(jsonPath("$.title").value("Monitored service not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"enabled\":null}",
            "{\"enabled\":true,\"name\":\"ignored\"}"
    })
    void should_rejectNotificationAuthority_when_requestIsNotBooleanOnly(String body)
            throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");

        mockMvc.perform(patch("/api/v1/services/{serviceId}/notification", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void should_rejectNotificationAuthority_when_serviceIdIsInvalid() throws Exception {
        mockMvc.perform(patch("/api/v1/services/not-a-uuid/notification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void should_rejectService_when_urlIsNotHttp() throws Exception {
        mockMvc.perform(post("/api/v1/services").contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace("https://homeops.example.invalid/health", "file:///private/path")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void should_rejectService_when_originIsNotAllowlisted() throws Exception {
        when(service.create(any())).thenThrow(new UnsafeServiceUrlException());

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsafe service URL"));
    }

    @Test
    void should_returnConflict_when_serviceNameAlreadyExists() throws Exception {
        when(service.create(any())).thenThrow(new DuplicateMonitoredServiceNameException());

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Service name already exists"));
    }

    @Test
    void should_returnCurrentStatuses_when_requested() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(service.currentStatuses()).thenReturn(List.of(new ServiceStatusResponse(
                id, "HomeOps", true, "HEALTHY", java.time.Instant.parse("2026-08-06T12:00:00Z"),
                200, 25, false)));

        mockMvc.perform(get("/api/v1/services/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("HEALTHY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "url"})
    void should_returnBadRequestWithoutServiceAccess_when_persistedTextContainsNul(String field)
            throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace("\"" + field + "\":\"",
                                "\"" + field + "\":\"\\u0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(service);
    }

    private static String validJson() { return """
            {"name":"HomeOps","url":"https://homeops.example.invalid/health","method":"GET","expectedStatus":200,"timeoutMs":3000,"intervalSeconds":30,"failureThreshold":3,"recoveryThreshold":2,"severity":"WARNING","enabled":true,"notificationEnabled":true}
            """; }
}
