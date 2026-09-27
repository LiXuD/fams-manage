package com.fams.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("fams-product")
@EnableScheduling
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@ConditionalOnExpression("${fams.attachments.cleanup-enabled:true}")
public class AttachmentCleanupScheduler {
    private final AttachmentCleanupService cleanup;

    private final AttachmentReconciliationService reconciliation;

    public AttachmentCleanupScheduler(AttachmentCleanupService cleanup, AttachmentReconciliationService reconciliation) {
        this.cleanup = cleanup;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${fams.attachments.cleanup-delay-ms:300000}",
            initialDelayString = "${fams.attachments.cleanup-initial-delay-ms:60000}")
    public void clean() {
        cleanup.expireDrafts(100);
        cleanup.deletePending(100);
        reconciliation.removeOrphans(100);
    }
}
