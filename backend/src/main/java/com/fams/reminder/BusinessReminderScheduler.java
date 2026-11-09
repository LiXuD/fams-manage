package com.fams.reminder;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class BusinessReminderScheduler {
    private final BusinessReminderService reminders;

    BusinessReminderScheduler(BusinessReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void refresh() {
        reminders.scan();
    }
}
