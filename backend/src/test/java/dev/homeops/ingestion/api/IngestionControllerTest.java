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
import dev.homeops.ingestion.SignalIngestionService;
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
    @Mock private SignalIngestionService signalService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new IngestionController(service, signalService))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void should_acceptDiskSignal_when_typedPayloadIsValid() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000030");
        when(signalService.accept(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDiskSignalJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void should_acceptHttpSignal_when_typedPayloadIsValid() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000031");
        when(signalService.accept(any())).thenReturn(new IngestionAcceptedResponse(id, false));

        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHttpSignalJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_typeIsUnknown() throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDiskSignalJson().replace("DISK_LOW", "CUSTOM_SIGNAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:malformed-request"));

        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_rawFieldIsUnsupported() throws Exception {
        String body = validDiskSignalJson().replace("\"thresholdPercent\":15",
                "\"thresholdPercent\":15,\"rawLog\":\"synthetic-private-value\"");

        String response = mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:malformed-request"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("synthetic-private-value", "rawLog");
        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_measurementsDoNotMatchType() throws Exception {
        String body = validDiskSignalJson().replace("\"thresholdPercent\":15",
                "\"thresholdPercent\":15,\"count\":10,\"windowSeconds\":300,\"thresholdCount\":5");

        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_numericMeasurementIsOutOfRange() throws Exception {
        for (String body : List.of(
                validDiskSignalJson().replace("\"availablePercent\":14", "\"availablePercent\":-1"),
                validDiskSignalJson().replace("\"thresholdPercent\":15", "\"thresholdPercent\":101"),
                validHttpSignalJson().replace("\"count\":12", "\"count\":-1"),
                validHttpSignalJson().replace("\"count\":12", "\"count\":1000001"),
                validHttpSignalJson().replace("\"windowSeconds\":300", "\"windowSeconds\":0"))) {
            mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
        }

        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_integerMeasurementOverflows() throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHttpSignalJson().replace("\"count\":12",
                                "\"count\":999999999999999999999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:malformed-request"));

        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_identityIsOverlongOrContainsNul() throws Exception {
        for (String body : List.of(
                validDiskSignalJson().replace("signal-alert-1", "a".repeat(129)),
                validDiskSignalJson().replace("episode-1", "b".repeat(129)),
                validDiskSignalJson().replace("form-dock", "c".repeat(129)),
                validDiskSignalJson().replace("form-dock", "form/dock"),
                validDiskSignalJson().replace("\"project\":\"form-dock\"",
                        "\"project\":\"\\u0000form-dock\""))) {
            mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
        }

        verifyNoInteractions(signalService);
    }

    @Test
    void should_rejectSignalWithoutServiceAccess_when_timestampIsOutsidePostgresqlRange() throws Exception {
        mockMvc.perform(post("/api/v1/internal/ingestion/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDiskSignalJson().replace("2026-08-27T01:02:03Z", OUTSIDE_POSTGRESQL_RANGE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(signalService);
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

    private static String validDiskSignalJson() {
        return """
                {
                  "eventKey":"signal-alert-1",
                  "episodeKey":"episode-1",
                  "project":"form-dock",
                  "signalType":"DISK_LOW",
                  "status":"ALERT",
                  "observedAt":"2026-08-27T01:02:03Z",
                  "availablePercent":14,
                  "thresholdPercent":15
                }
                """;
    }

    private static String validHttpSignalJson() {
        return """
                {
                  "eventKey":"signal-http-alert-1",
                  "episodeKey":"episode-http-1",
                  "project":"form-dock",
                  "signalType":"HTTP_5XX_BURST",
                  "status":"ALERT",
                  "observedAt":"2026-08-27T01:02:03Z",
                  "count":12,
                  "windowSeconds":300,
                  "thresholdCount":10
                }
                """;
    }
}
