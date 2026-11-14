package com.fams.reminder;

import com.fams.auth.WorkforceAvailabilityChanged;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Profile("fams-product")
class WorkforceReminderListener {
    private static final Logger LOG = LoggerFactory.getLogger(WorkforceReminderListener.class);
    private final ReassignmentReminderService reassignments;
    private final ConfigurationAnomalyService anomalies;

    WorkforceReminderListener(ReassignmentReminderService reassignments, ConfigurationAnomalyService anomalies) {
        this.reassignments = reassignments;
        this.anomalies = anomalies;
    }

    @TransactionalEventListener
    void refresh(WorkforceAvailabilityChanged event) {
        try {
            reassignments.scan();
            anomalies.scan();
        } catch (RuntimeException failure) {
            LOG.warn("Workforce reminder refresh will retry on the scheduled scan: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
