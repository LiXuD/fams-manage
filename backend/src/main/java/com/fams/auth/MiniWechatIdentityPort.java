package com.fams.auth;

/** A server-side exchange adapter; never accepts a client-supplied openid as proof. */
public interface MiniWechatIdentityPort {

    /**
     * Exchanges the client-supplied temporary login code for a WeChat identity verified
     * server-side against the provider.
     *
     * @param code single-use temporary login code from the mini-program client
     * @return the verified identity; the code itself is never treated as identity proof
     */
    Identity exchange(String code);

    /** Server-verified WeChat identity pair used for binding ownership checks. */
    record Identity(String appId, String openId) {
        /**
         * Rejects identities with a blank or oversized appId or openId, so only values obtained
         * from a verified provider response can enter the binding flow.
         *
         * @throws IllegalArgumentException when either component is null, blank or longer than
         *         128 characters
         */
        public Identity {
            if (appId == null || appId.isBlank() || appId.length() > 128
                    || openId == null || openId.isBlank() || openId.length() > 128) {
                throw new IllegalArgumentException("Invalid verified WeChat identity.");
            }
        }
    }
}
