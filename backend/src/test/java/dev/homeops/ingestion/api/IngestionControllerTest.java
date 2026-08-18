package dev.homeops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.common.PostgresqlTimestampRange;
import dev.homeops.ingestion.IngestionService;
import java.util.List;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
    void should_returnBadRequestWithoutServiceAccess_when_timestampRoundingCarryExitsPostgresqlRange()
            throws Exception {
        String carryPastEnd = PostgresqlTimestampRange.endExclusive().minusNanos(1).toString();
        for (String field : List.of("startedAt", "finishedAt")) {
            mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withTimestamp(validDeploymentJson(), field, carryPastEnd)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
        }
        for (String field : List.of("startedAt", "finishedAt", "expiresAt", "restoreTestedAt")) {
            mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withTimestamp(validBackupJson(), field, carryPastEnd)))
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

    @ParameterizedTest
    @MethodSource("deploymentTextFields")
    void should_returnBadRequestWithoutServiceAccess_when_deploymentTextContainsNul(String field)
            throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withNul(validDeploymentJson(), field)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("backupTextFields")
    void should_returnBadRequestWithoutServiceAccess_when_backupTextContainsNul(String field)
            throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withNul(validBackupJson(), field)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(service);
    }

    @Test
    void should_acceptDeployment_when_textContainsNormalUnicode() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000021");
        when(service.acceptDeployment(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeploymentJson().replace("homeops", "홈옵스")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void should_acceptBackup_when_textContainsNormalUnicode() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000022");
        when(service.acceptBackup(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBackupJson().replace("\"failureSummary\":\"none\"",
                                "\"failureSummary\":\"정상 백업\"")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void should_acceptDeployment_when_optionalTextIsNull() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000023");
        when(service.acceptDeployment(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeploymentJson().replace("\"branch\":\"main\"", "\"branch\":null")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void should_acceptBackup_when_optionalTextIsNull() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000024");
        when(service.acceptBackup(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBackupJson().replace("\"logicalLocation\":\"homeops/2026-08-06.dump\"",
                                "\"logicalLocation\":null")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @ParameterizedTest
    @MethodSource("invalidBackupLogicalLocations")
    void should_returnSafeBadRequestWithoutServiceAccess_when_backupLogicalLocationIsInvalid(
            String logicalLocation) throws Exception {
        String requestBody = withLogicalLocation(validBackupJson(), logicalLocation);

        String responseBody = mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(logicalLocation);
        verifyNoInteractions(service);
    }

    @Test
    void should_returnSafeBadRequestWithoutServiceAccess_when_backupLogicalLocationIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withLogicalLocation(validBackupJson(), "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("validBackupLogicalLocations")
    void should_acceptBackup_when_logicalLocationIsValidRelativeIdentifier(String logicalLocation) throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000025");
        when(service.acceptBackup(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withLogicalLocation(validBackupJson(), logicalLocation)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).acceptBackup(argThat(request -> logicalLocation.equals(request.logicalLocation())));
    }

    private static String withOutsideTimestamp(String json, String field) {
        return withTimestamp(json, field, OUTSIDE_POSTGRESQL_RANGE);
    }

    private static String withTimestamp(String json, String field, String timestamp) {
        return json.replace("\"" + field + "\":\"2026-08-06T01:00:00Z\"",
                "\"" + field + "\":\"" + timestamp + "\"");
    }

    private static String withNul(String json, String field) {
        return json.replace("\"" + field + "\":\"", "\"" + field + "\":\"\\u0000");
    }

    private static String withLogicalLocation(String json, String logicalLocation) {
        return json.replace("\"logicalLocation\":\"homeops/2026-08-06.dump\"",
                "\"logicalLocation\":\"" + logicalLocation + "\"");
    }

    private static Stream<String> deploymentTextFields() {
        return Stream.of("eventKey", "project", "environment", "branch", "commitSha", "imageTag",
                "previousCommitSha", "failureStage", "failureSummary", "actor", "workflowRunId",
                "workflowRunUrl");
    }

    private static Stream<String> backupTextFields() {
        return Stream.of("eventKey", "project", "databaseType", "logicalLocation", "failureSummary",
                "restoreTestStatus");
    }

    private static Stream<String> invalidBackupLogicalLocations() {
        return Stream.of(
                "/synthetic/backup.dump",
                "C:/synthetic/backup.dump",
                "../synthetic/backup.dump",
                "synthetic/../../backup.dump",
                "synthetic backup.dump",
                "a".repeat(257));
    }

    private static Stream<String> validBackupLogicalLocations() {
        return Stream.of("project/2026-08-18/backup.dump", "a".repeat(256));
    }

    private static String validDeploymentJson() {
        return """
                {
                  "eventKey":"deployment-1",
                  "project":"homeops",
                  "environment":"production",
                  "branch":"main",
                  "commitSha":"0123456789012345678901234567890123456789",
                  "imageTag":"sha-0123456",
                  "previousCommitSha":"1111111111111111111111111111111111111111",
                  "status":"SUCCESS",
                  "startedAt":"2026-08-06T01:00:00Z",
                  "finishedAt":"2026-08-06T01:00:00Z",
                  "failureStage":"deploy",
                  "failureSummary":"none",
                  "actor":"github-actions",
                  "workflowRunId":"123",
                  "workflowRunUrl":"https://example.invalid/runs/123",
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
                  "logicalLocation":"homeops/2026-08-06.dump",
                  "status":"SUCCESS",
                  "startedAt":"2026-08-06T01:00:00Z",
                  "finishedAt":"2026-08-06T01:00:00Z",
                  "expiresAt":"2026-08-06T01:00:00Z",
                  "failureSummary":"none",
                  "restoreTestedAt":"2026-08-06T01:00:00Z",
                  "restoreTestStatus":"SUCCESS"
                }
                """;
    }
}
