package dev.homeops.system.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.common.ApiExceptionHandler;
import dev.homeops.metrics.InvalidMetricHistoryPeriodException;
import dev.homeops.metrics.MetricHistoryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MetricHistoryControllerTest {
    @Mock private AgentSnapshotService agentSnapshotService;
    @Mock private MetricHistoryService metricHistoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SystemController(agentSnapshotService, metricHistoryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1h", "6h", "24h", "7d"})
    void should_returnMetricHistory_when_periodIsSupported(String period) throws Exception {
        when(metricHistoryService.history(period)).thenReturn(response(period));

        mockMvc.perform(get("/api/v1/system/metrics/history").param("period", period))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(period))
                .andExpect(jsonPath("$.from").value("2026-08-17T06:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-08-17T12:00:00Z"))
                .andExpect(jsonPath("$.bucketSeconds").value(300))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-08-17T11:55:00Z"))
                .andExpect(jsonPath("$.points[0].sampleCount").value(12))
                .andExpect(jsonPath("$.points[0].diskUsedBytes").value(250_000));
    }

    @Test
    void should_returnBadRequest_when_periodIsMissing() throws Exception {
        when(metricHistoryService.history(null)).thenThrow(new InvalidMetricHistoryPeriodException());

        mockMvc.perform(get("/api/v1/system/metrics/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
    }

    @Test
    void should_returnBadRequest_when_periodIsUnsupported() throws Exception {
        when(metricHistoryService.history("30d")).thenThrow(new InvalidMetricHistoryPeriodException());

        mockMvc.perform(get("/api/v1/system/metrics/history").param("period", "30d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));
    }

    private static MetricHistoryResponse response(String period) {
        return new MetricHistoryResponse(
                period,
                Instant.parse("2026-08-17T06:00:00Z"),
                Instant.parse("2026-08-17T12:00:00Z"),
                300,
                List.of(new MetricHistoryResponse.MetricHistoryPoint(
                        Instant.parse("2026-08-17T11:55:00Z"),
                        12,
                        12.5,
                        20.0,
                        16_000,
                        8_000,
                        9_000,
                        1_000_000,
                        250_000)));
    }
}
