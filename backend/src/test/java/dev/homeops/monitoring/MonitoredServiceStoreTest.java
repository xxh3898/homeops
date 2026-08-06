package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.common.DuplicateMonitoredServiceNameException;
import dev.homeops.monitoring.api.MonitoredServiceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class MonitoredServiceStoreTest {
    @Mock private JdbcTemplate jdbc;

    @Test
    void should_rejectDuplicateName_when_databaseConflictPreventsInsert() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> store.create(request()))
                .isInstanceOf(DuplicateMonitoredServiceNameException.class);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), any(Object[].class));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "ON CONFLICT ON CONSTRAINT uk_monitored_service_name DO NOTHING");
    }

    private static MonitoredServiceRequest request() {
        return new MonitoredServiceRequest("HomeOps", "https://homeops.example.invalid/health",
                MonitoredServiceRequest.Method.GET, 200, 3_000, 30, 3, 2,
                MonitoredServiceRequest.Severity.WARNING, true, true);
    }
}
