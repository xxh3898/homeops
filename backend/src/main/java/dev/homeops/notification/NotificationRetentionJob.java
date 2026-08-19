package dev.homeops.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class NotificationRetentionJob {
    private final NotificationOutboxTransactions transactions;

    NotificationRetentionJob(NotificationOutboxTransactions transactions) {
        this.transactions = transactions;
    }

    @Scheduled(cron = "${homeops.notifications.cleanup-cron:0 41 3 * * *}", zone = "UTC")
    public void removeExpiredNotifications() {
        transactions.deleteExpiredTerminalRows();
    }
}
