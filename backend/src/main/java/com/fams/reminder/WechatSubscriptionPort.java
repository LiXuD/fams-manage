package com.fams.reminder;

import java.util.Map;

/** Server-side adapter for sending one WeChat subscription message. */
@FunctionalInterface
public interface WechatSubscriptionPort {
    enum Result { ACCEPTED, RETRYABLE, REJECTED, UNKNOWN }

    /**
     * Resolve the WeChat recipient from the verified server-side binding. Reject revoked, replaced
     * or mismatched bindings and unavailable consent. Treat ACCEPTED as provider acceptance.
     * Return RETRYABLE only when non-acceptance is known. For ambiguous results, reconcile by
     * deliveryKey where supported, or return UNKNOWN without sending a second message.
     */
    Result send(Delivery delivery);

    /**
     * The caller supplies a stable delivery key and an authorized recipient and business page.
     * Template values contain approved display data. No client-supplied openid or token is accepted.
     */
    record Delivery(long recipientAccountId, long bindingId, String templateId, String page,
            Map<String, String> templateValues, String deliveryKey) {
        public Delivery {
            templateValues = Map.copyOf(templateValues);
        }
    }
}
