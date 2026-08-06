package dev.homeops.ingestion.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.ingestion.IngestionService;
import java.util.List;
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
class IngestionControllerTest {
    private static final String OUTSIDE_POSTGRESQL_RANGE = "+300000-01-01T00:00:00Z";

    @Mock private IngestionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new IngestionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void should_returnBadRequestWithoutServiceAccess_when_deploymentTimestampIsOutsidePostgresqlRange()
            throws Exception {
        for (String field : List.of("startedAt", "finishedAt")) {
            mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withOutsideTimestamp(validDeploymentJson(), field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void should_returnBadRequestWithoutServiceAccess_when_backupTimestampIsOutsidePostgresqlRange()
            throws Exception {
        for (String field : List.of("startedAt", "finishedAt", "expiresAt", "restoreTestedAt")) {
            mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withOutsideTimestamp(validBackupJson(), field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void should_acceptDeployment_when_optionalTimestampIsNull() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000020");
        when(service.acceptDeployment(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeploymentJson().replace("\"finishedAt\":\"2026-08-06T01:00:00Z\"",
                                "\"finishedAt\":null")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    private static String withOutsideTimestamp(String json, String field) {
        return json.replace("\"" + field + "\":\"2026-08-06T01:00:00Z\"",
                "\"" + field + "\":\"" + OUTSIDE_POSTGRESQL_RANGE + "\"");
    }

    private static String validDeploymentJson() {
        return """
                {
                  "eventKey":"deployment-1",
                  "project":"homeops",
                  "environment":"production",
                  "commitSha":"0123456789012345678901234567890123456789",
                  "status":"SUCCESS",
                  "startedAt":"2026-08-06T01:00:00Z",
                  "finishedAt":"2026-08-06T01:00:00Z",
                  "rollback":false
                }
                """;
    }

    private static String validBackupJson() {
        return """
                {
                  "eventKey":"backup-1",
                  "project":"homeops",
                  "databaseType":"POSTGRESQL",
                  "status":"SUCCESS",
                  "startedAt":"2026-08-06T01:00:00Z",
                  "finishedAt":"2026-08-06T01:00:00Z",
                  "expiresAt":"2026-08-06T01:00:00Z",
                  "restoreTestedAt":"2026-08-06T01:00:00Z"
                }
                """;
    }
}
