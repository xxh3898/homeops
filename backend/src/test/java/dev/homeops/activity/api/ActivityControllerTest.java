package dev.homeops.activity.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.activity.ActivityService;
import dev.homeops.activity.ActivityStore;
import dev.homeops.common.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
class ActivityControllerTest {
    @Mock private ActivityStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ActivityController(new ActivityService(store)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void should_returnBadRequest_when_cursorSnapshotViolatesPostgresSemantics() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("cursor", cursorWithVisibilitySnapshot("2:1:")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:invalid-activity-cursor"));

        verifyNoInteractions(store);
    }

    @Test
    void should_returnBadRequest_when_cursorTimestampIsOutsidePostgresqlRange() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("cursor", cursorWithTimestamps(
                        "2026-08-06T12:00:00Z", "+1000000000-12-31T23:59:59.999999999Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:invalid-activity-cursor"));

        verifyNoInteractions(store);
    }

    @Test
    void should_returnBadRequest_when_cursorSortKeyIsNotServerGeneratedFormat() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("cursor", cursorWithSortKey(
                        "DEPLOYMENT:10000000-0000-0000-0000-000000000001\u0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:invalid-activity-cursor"));

        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "deployment", "UNKNOWN", "ALL"})
    void should_returnBadRequestWithoutStoreAccess_when_typeIsNotExactSupportedValue(String type) throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("type", type))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:invalid-activity-type"));

        verifyNoInteractions(store);
    }

    @Test
    void should_returnBadRequestWithoutStoreAccess_when_multipleTypesAreProvided() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("type", "DEPLOYMENT", "BACKUP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:invalid-activity-type"));

        verifyNoInteractions(store);
    }

    private static String cursorWithVisibilitySnapshot(String snapshot) {
        String value = "2026-08-06T12:00:00Z\n" + snapshot + "\n2026-08-06T12:00:00Z\n"
                + "DEPLOYMENT:10000000-0000-0000-0000-000000000001";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String cursorWithTimestamps(String snapshotAt, String occurredAt) {
        String value = snapshotAt + "\n100:200:150\n" + occurredAt + "\n"
                + "DEPLOYMENT:10000000-0000-0000-0000-000000000001";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String cursorWithSortKey(String sortKey) {
        String value = "2026-08-06T12:00:00Z\n100:200:150\n2026-08-06T12:00:00Z\n" + sortKey;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
