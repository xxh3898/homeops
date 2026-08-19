package dev.homeops.notification;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutbox {
    private final NotificationOutboxTransactions transactions;

    NotificationOutbox(NotificationOutboxTransactions transactions) {
        this.transactions = transactions;
    }

    public UUID enqueue(NotificationIntent intent) {
        return transactions.enqueue(intent);
    }
}
