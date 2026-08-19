package dev.homeops.notification;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRetentionJobTest {
    @Mock private NotificationOutboxTransactions transactions;

    @Test
    void should_deleteOnlyExpiredNotificationRows_when_cleanupRuns() {
        NotificationRetentionJob job = new NotificationRetentionJob(transactions);

        job.removeExpiredNotifications();

        verify(transactions).deleteExpiredTerminalRows();
    }
}
