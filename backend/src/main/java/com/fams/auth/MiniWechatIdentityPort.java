package com.fams.auth;

/** A server-side exchange adapter; never accepts a client-supplied openid as proof. */
public interface MiniWechatIdentityPort {

    Identity exchange(String code);

    record Identity(String appId, String openId) {
        public Identity {
            if (appId == null || appId.isBlank() || appId.length() > 128
                    || openId == null || openId.isBlank() || openId.length() > 128) {
                throw new IllegalArgumentException("Invalid verified WeChat identity.");
            }
        }
    }
}
