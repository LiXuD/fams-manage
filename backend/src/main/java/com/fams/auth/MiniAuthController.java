package com.fams.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.api.MiniAuthApi;
import com.fams.product.contract.model.MiniLoginRequest;
import com.fams.product.contract.model.MiniSessionGrant;
import com.fams.product.contract.model.MiniSessionView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
class MiniAuthController implements MiniAuthApi {

    static final String HEADER = "X-FAMS-Mini-Session";
    static final String CURRENT = "fams.auth.mini-current";

    private final MiniSessionService sessions;
    private final ObjectProvider<MiniWechatIdentityPort> identityPort;
    private final HttpServletRequest request;

    MiniAuthController(MiniSessionService sessions, ObjectProvider<MiniWechatIdentityPort> identityPort,
            HttpServletRequest request) {
        this.sessions = sessions;
        this.identityPort = identityPort;
        this.request = request;
    }

    @Override
    public ResponseEntity<MiniSessionGrant> miniLogin(MiniLoginRequest input) {
        if (input.getCode() == null || input.getCode().isBlank()) {
            throw new AuthOperationException("INVALID_REQUEST", "A login code is required.", 400);
        }
        MiniWechatIdentityPort port = identityPort.getIfAvailable();
        if (port == null) {
            throw unavailable();
        }
        MiniWechatIdentityPort.Identity identity;
        try {
            identity = port.exchange(input.getCode());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        if (identity == null) {
            throw unavailable();
        }
        MiniSessionService.Grant grant = sessions.start(identity, AuthRequestContext.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MiniSessionGrant(
                MiniSessionGrant.BindingStatusEnum.fromValue(grant.bindingStatus()), grant.sessionToken(),
                grant.accountId(), grant.bindingId(), utc(grant.idleExpiresAt()), utc(grant.absoluteExpiresAt())));
    }

    @Override
    public ResponseEntity<MiniSessionView> miniCurrentSession() {
        MiniSessionService.Current current = (MiniSessionService.Current) request.getAttribute(CURRENT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MiniSessionView(
                MiniSessionView.BindingStatusEnum.BOUND, Long.toString(current.account().id()), current.bindingId(),
                current.bindingVersion(), utc(current.idleExpiresAt()), utc(current.absoluteExpiresAt())));
    }

    @Override
    public ResponseEntity<Void> miniLogout() {
        sessions.logout(request.getHeader(HEADER), AuthRequestContext.requestId(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private AuthOperationException unavailable() {
        return new AuthOperationException("MINI_WECHAT_UNAVAILABLE", "WeChat login is unavailable.", 503);
    }
}
