package com.fams.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persisted READY rows are retried after restart; every transition has its own transaction. */
@Component
@Profile("fams-product")
final class InventorySchedule {
    private static final Logger LOG = LoggerFactory.getLogger(InventorySchedule.class);
    private final InventoryService service;

    InventorySchedule(InventoryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fams.inventory.start-poll-ms:10000}")
    void run() {
        try {
            for (int count = 0; count < 50 && service.startDue(); count++) {
                /* bounded catch-up */
            }
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Inventory scheduled start failed; persisted READY rows remain retryable ({})",
                    failure.getClass().getSimpleName());
        }
    }
}
