package com.fams.reminder;

import com.fams.auth.MiniNotificationSmsPort;
import com.fams.job.DurableJobService;
import com.fams.masterdata.NotificationChannelConfigPort;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class NotificationDeliveryWorker {
    private static final int MAX_ATTEMPTS = 5;
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private final DurableJobService jobs;
    private final NotificationDeliveryQueue queue;
    private final ObjectProvider<MiniNotificationSmsPort> provider;
    private final ObjectProvider<WechatSubscriptionPort> wechatProvider;
    private final NotificationChannelConfigPort channels;
    private final String owner = UUID.randomUUID().toString();

    NotificationDeliveryWorker(DurableJobService jobs, NotificationDeliveryQueue queue,
            ObjectProvider<MiniNotificationSmsPort> provider,
            ObjectProvider<WechatSubscriptionPort> wechatProvider,
            NotificationChannelConfigPort channels) {
        this.jobs = jobs;
        this.queue = queue;
        this.provider = provider;
        this.wechatProvider = wechatProvider;
        this.channels = channels;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void poll() {
        var sender = provider.getIfUnique();
        String template = channels.currentTemplates().smsTemplate();
        if (sender != null && template.matches("[A-Za-z0-9_-]{1,80}")) {
            queue.enqueue(template);
            for (int index = 0; index < 100; index++) {
                var claimed = jobs.claim(NotificationDeliveryQueue.KIND, owner, Duration.ofMinutes(2));
                if (claimed.isEmpty()) {
                    break;
                }
                send(sender, claimed.orElseThrow());
            }
        }
        var wechatSender = wechatProvider.getIfUnique();
        String wechatTemplate = channels.currentTemplates().wechatTemplate();
        if (wechatSender != null && wechatTemplate.matches("[A-Za-z0-9_-]{1,80}")) {
            queue.enqueueWechat(wechatTemplate);
            for (int index = 0; index < 100; index++) {
                var claimed = jobs.claim(NotificationDeliveryQueue.WECHAT_KIND, owner, Duration.ofMinutes(2));
                if (claimed.isEmpty()) {
                    break;
                }
                sendWechat(wechatSender, claimed.orElseThrow());
            }
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

    private void sendWechat(WechatSubscriptionPort sender, DurableJobService.Claim claim) {
        String template = queue.eligibleWechatTemplate(claim);
        if (template == null) {
            jobs.fail(claim, "RECIPIENT_UNAVAILABLE_OR_READ", false);
            return;
        }
        var context = queue.wechatContext(claim);
        Long bindingId = context == null ? null : queue.activeBinding(context.accountId());
        if (context == null || context.grantId() == null || bindingId == null
                || !queue.consumeGrant(context.grantId(), claim.id().toString())) {
            jobs.fail(claim, "GRANT_UNAVAILABLE", false);
            return;
        }
        WechatSubscriptionPort.Result result;
        try {
            result = sender.send(new WechatSubscriptionPort.Delivery(context.accountId(), bindingId, template,
                    page(context.businessType()),
                    // Field keys are placeholders until the real provider template is wired in D6-09B/10B.
                    Map.of("thing1", context.businessType(), "time2",
                            DISPLAY_TIME.format(context.deadline().toInstant())),
                    claim.id().toString()));
        } catch (RuntimeException failure) {
            result = WechatSubscriptionPort.Result.UNKNOWN;
        }
        if (result == WechatSubscriptionPort.Result.ACCEPTED) {
            jobs.complete(claim, "WECHAT_ACCEPTED", () -> queue.accepted(claim));
            return;
        }
        boolean rejected = result == WechatSubscriptionPort.Result.REJECTED;
        String code = rejected ? "WECHAT_REJECTED"
                : result == WechatSubscriptionPort.Result.RETRYABLE ? "WECHAT_RETRYABLE" : "WECHAT_RESULT_UNKNOWN";
        jobs.fail(claim, code, !rejected && claim.attempts() < MAX_ATTEMPTS);
    }

    private static String page(String businessType) {
        return switch (businessType) {
            case "REQUISITION_ALLOCATION" -> "pages/requests/index";
            case "LOAN" -> "pages/loans/index";
            case "INVENTORY_TASK" -> "pages/inventory-tasks/index";
            case "TRANSFER_ITEM" -> "pages/transfer-receipts/index";
            case "INBOUND" -> "pages/receipts/index";
            case "REPAIR_FACTORY_ROUND" -> "pages/repairs/index";
            case "SCRAP" -> "pages/scrap-requests/index";
            case "LOST_REPORT" -> "pages/loss-reports/index";
            default -> "pages/messages/index";
        };
    }
}
