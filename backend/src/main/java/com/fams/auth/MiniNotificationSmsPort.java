package com.fams.auth;

/** Business SMS capability. A provider can implement MiniSmsPort separately for verification codes. */
@FunctionalInterface
public interface MiniNotificationSmsPort {
    enum Result {
        /** The provider accepted the request. This does not prove handset delivery. */
        ACCEPTED,
        /** The provider confirmed it did not accept the request. The same key may be retried. */
        RETRYABLE,
        /** A terminal rejection, including an ineligible recipient. */
        REJECTED,
        /** The provider may have accepted the request. Reconcile or deduplicate before resubmitting. */
        UNKNOWN
    }

    /**
     * A provider must deduplicate repeated keys, including after an unknown result, or return
     * the reconciled status for the original request. Resolve the recipient using current directory facts.
     */
    Result sendReminder(long recipientAccountId, String templateCode, String deliveryKey);
}
