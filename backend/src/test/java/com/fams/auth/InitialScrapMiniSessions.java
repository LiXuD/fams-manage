package com.fams.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates isolated opaque grants for the native initial-scrap HTTP regression. */
public final class InitialScrapMiniSessions {
    private InitialScrapMiniSessions() { }

    public static Map<String, Object> issue(ApplicationContext context, JdbcTemplate db, Map<String, Long> accounts) {
        var sessions = context.getBean(MiniSessionService.class);
        var grants = new LinkedHashMap<String, Object>();
        for (var account : accounts.entrySet()) {
            var identity = new MiniWechatIdentityPort.Identity("owned-initial-scrap", account.getKey());
            db.update("INSERT INTO fams_mini_binding(account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                    account.getValue(), sessions.identityHash(identity));
            var grant = sessions.start(identity, UUID.randomUUID());
            grants.put(account.getKey(), Map.of("sessionToken", grant.sessionToken(),
                    "accountId", account.getValue().toString(), "bindingId", grant.bindingId()));
        }
        return grants;
    }
}
