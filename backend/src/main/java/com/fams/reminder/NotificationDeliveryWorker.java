package com.fams.reminder;

import com.fams.auth.MiniNotificationSmsPort;
import com.fams.job.DurableJobService;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class NotificationDeliveryWorker {
    private static final int MAX_ATTEMPTS = 5;
    private final DurableJobService jobs;
    private final NotificationDeliveryQueue queue;
    private final ObjectProvider<MiniNotificationSmsPort> provider;
    private final String template;
    private final String owner = UUID.randomUUID().toString();

    NotificationDeliveryWorker(DurableJobService jobs, NotificationDeliveryQueue queue,
            ObjectProvider<MiniNotificationSmsPort> provider,
            @Value("${fams.notification.sms-template:}") String template) {
        this.jobs = jobs;
        this.queue = queue;
        this.provider = provider;
        this.template = template;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void poll() {
        var sender = provider.getIfUnique();
        if (sender == null || !template.matches("[A-Za-z0-9_-]{1,80}")) {
            return;
        }
        queue.enqueue(template);
        for (int index = 0; index < 100; index++) {
            var claimed = jobs.claim(NotificationDeliveryQueue.KIND, owner, Duration.ofMinutes(2));
            if (claimed.isEmpty()) {
                return;
            }
            send(sender, claimed.orElseThrow());
        }
    }

    private void send(MiniNotificationSmsPort sender, DurableJobService.Claim claim) {
        String eligibleTemplate = queue.eligibleTemplate(claim);
        if (eligibleTemplate == null) {
            jobs.fail(claim, "RECIPIENT_UNAVAILABLE_OR_READ", false);
            return;
        }
        MiniNotificationSmsPort.Result result;
        try {
            result = sender.sendReminder(claim.actorId(), eligibleTemplate, claim.id().toString());
        } catch (RuntimeException failure) {
            result = MiniNotificationSmsPort.Result.UNKNOWN;
        }
        if (result == MiniNotificationSmsPort.Result.ACCEPTED) {
            jobs.complete(claim, "SMS_ACCEPTED", () -> queue.accepted(claim));
            return;
        }
        boolean rejected = result == MiniNotificationSmsPort.Result.REJECTED;
        String code = rejected ? "SMS_REJECTED"
                : result == MiniNotificationSmsPort.Result.RETRYABLE ? "SMS_RETRYABLE" : "SMS_RESULT_UNKNOWN";
        jobs.fail(claim, code, !rejected && claim.attempts() < MAX_ATTEMPTS);
    }
}
