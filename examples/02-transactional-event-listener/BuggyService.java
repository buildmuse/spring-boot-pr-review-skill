package com.example.notifications;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

@Service
public class SubscriptionReminderService {

    private final SubscriptionRepository subscriptionRepository;
    private final ApplicationEventPublisher events;

    public SubscriptionReminderService(SubscriptionRepository subscriptionRepository,
                                       ApplicationEventPublisher events) {
        this.subscriptionRepository = subscriptionRepository;
        this.events = events;
    }

    // Runs once an hour. No @Transactional — this is a top-level scheduled job
    // and we do not want a long-running transaction held open for the entire
    // sweep across all tenants.
    @Scheduled(cron = "0 0 * * * *")
    public void sweepExpiringSubscriptions() {
        List<Subscription> expiring =
            subscriptionRepository.findExpiringSoon(Instant.now());

        for (Subscription s : expiring) {
            // Publish an event so the email service can react asynchronously.
            events.publishEvent(new SubscriptionExpiringEvent(s.getId(),
                                                              s.getTenantId(),
                                                              s.getOwnerEmail()));
        }
    }
}

@Service
class SubscriptionEmailListener {

    private final EmailSender emailSender;

    SubscriptionEmailListener(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @TransactionalEventListener
    public void onSubscriptionExpiring(SubscriptionExpiringEvent event) {
        emailSender.sendExpiryReminder(event.tenantId(), event.ownerEmail(),
                                       event.subscriptionId());
    }
}
