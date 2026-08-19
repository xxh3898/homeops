package dev.homeops.monitoring.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.monitoring.MonitoredServiceNotFoundException;
import dev.homeops.monitoring.MonitoredServiceStore;
import dev.homeops.monitoring.SafeServiceUrlPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {
    @Mock private MonitoredServiceStore store;
    @Mock private SafeServiceUrlPolicy policy;

    @Test
    void should_updateNotificationAuthority_when_valueChanges() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(store.findNotificationAuthorityForUpdate(id)).thenReturn(Optional.of(false));
        MonitoringService service = new MonitoringService(store, policy);

        MonitoredServiceNotificationResponse response =
                service.updateNotificationAuthority(id, true);

        assertThat(response).isEqualTo(new MonitoredServiceNotificationResponse(id, true));
        verify(store).updateNotificationAuthority(id, true);
    }

    @Test
    void should_notWriteNotificationAuthority_when_valueIsUnchanged() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000100");
        when(store.findNotificationAuthorityForUpdate(id)).thenReturn(Optional.of(true));
        MonitoringService service = new MonitoringService(store, policy);

        MonitoredServiceNotificationResponse response =
                service.updateNotificationAuthority(id, true);

        assertThat(response.notificationEnabled()).isTrue();
        verify(store, never()).updateNotificationAuthority(id, true);
    }

    @Test
    void should_failClosed_when_monitoredServiceDoesNotExist() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000404");
        when(store.findNotificationAuthorityForUpdate(id)).thenReturn(Optional.empty());
        MonitoringService service = new MonitoringService(store, policy);

        assertThatThrownBy(() -> service.updateNotificationAuthority(id, true))
                .isInstanceOf(MonitoredServiceNotFoundException.class)
                .hasMessage("Monitored service does not exist");
        verify(store, never()).updateNotificationAuthority(id, true);
    }
}
