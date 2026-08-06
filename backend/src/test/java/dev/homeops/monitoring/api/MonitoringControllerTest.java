package dev.homeops.monitoring.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.common.DuplicateMonitoredServiceNameException;
import dev.homeops.monitoring.SafeServiceUrlPolicy.UnsafeServiceUrlException;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private static String validJson() { return """
            {"name":"HomeOps","url":"https://homeops.example.invalid/health","method":"GET","expectedStatus":200,"timeoutMs":3000,"intervalSeconds":30,"failureThreshold":3,"recoveryThreshold":2,"severity":"WARNING","enabled":true,"notificationEnabled":true}
            """; }
}
