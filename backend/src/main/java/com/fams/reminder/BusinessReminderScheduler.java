package com.fams.reminder;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class BusinessReminderScheduler {
    private final BusinessReminderService reminders;
    private final DutyEscalationService escalations;

    BusinessReminderScheduler(BusinessReminderService reminders, DutyEscalationService escalations) {
        this.reminders = reminders;
        this.escalations = escalations;
    }

    /** The ladder must exist before the escalation reads it, so one tick runs the two scans in that order. */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void refresh() {
        reminders.scan();
        escalations.escalate();
    }
}
