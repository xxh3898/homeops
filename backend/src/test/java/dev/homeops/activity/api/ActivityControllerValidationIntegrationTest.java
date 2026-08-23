package dev.homeops.activity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.homeops.activity.ActivityService;
import dev.homeops.activity.ActivityTypeFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@WebMvcTest(ActivityController.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityControllerValidationIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ActivityService service;

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void should_returnValidationProblem_when_limitIsOutsideAllowedRange(String limit) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activity").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void should_acceptBoundaryLimit_when_limitIsAllowed(int limit) throws Exception {
        when(service.page(isNull(), eq(ActivityTypeFilter.ALL), eq(limit)))
                .thenReturn(new ActivityPageResponse(List.of(), null, null));

        mockMvc.perform(get("/api/v1/activity").param("limit", String.valueOf(limit)))
                .andExpect(status().isOk());

        verify(service).page(null, ActivityTypeFilter.ALL, limit);
    }

    @Test
    void should_returnValidationProblem_when_limitIsNotNumeric() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errorCount").value(1));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPLOYMENT", "BACKUP", "INCIDENT", "AGENT", "CONTAINER_ACTION"})
    void should_acceptExactSingleActivityType(String type) throws Exception {
        ActivityTypeFilter filter = ActivityTypeFilter.valueOf(type);
        when(service.page(isNull(), eq(filter), eq(25)))
                .thenReturn(new ActivityPageResponse(List.of(), null, null));

        mockMvc.perform(get("/api/v1/activity").param("type", type))
                .andExpect(status().isOk());

        verify(service).page(null, filter, 25);
    }

    @Test
    void should_keepLimitBound_when_activityTypeIsFiltered() throws Exception {
        mockMvc.perform(get("/api/v1/activity").param("type", "AGENT").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:homeops:problem:validation"));

        verifyNoInteractions(service);
    }
}
