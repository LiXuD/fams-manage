package com.fams.auth;

/** Notification capability of an SMS provider, separate from sending a binding challenge. */
public interface MiniNotificationSmsPort extends MiniSmsPort {
    enum Result { ACCEPTED, RETRYABLE, REJECTED, UNKNOWN }

    /**
     * A provider must deduplicate repeated keys, including after an unknown result, or return
     * the reconciled status for the original request. Resolve the recipient using current directory facts.
     */
    Result sendReminder(long recipientAccountId, String templateCode, String deliveryKey);
}
