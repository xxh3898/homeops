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

    private static String cursorWithVisibilitySnapshot(String snapshot) {
        String value = "2026-08-06T12:00:00Z\n" + snapshot + "\n2026-08-06T12:00:00Z\nDEPLOYMENT:1";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
