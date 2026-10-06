package com.fams.auth;

import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.api.MiniBindingApi;
import com.fams.product.contract.model.MiniBindingChallenge;
import com.fams.product.contract.model.MiniBindingChallengeRequest;
import com.fams.product.contract.model.MiniBindingResult;
import com.fams.product.contract.model.MiniBindingSupport;
import com.fams.product.contract.model.MiniBindingVerifyRequest;
import com.fams.product.contract.model.MiniUnbindRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
class MiniBindingController implements MiniBindingApi {
    private final MiniBindingService bindings;
    private final ObjectProvider<MiniWechatIdentityPort> wechat;
    private final ObjectProvider<MiniSmsPort> sms;
    private final HttpServletRequest request;
    private final String supportText;

    MiniBindingController(MiniBindingService bindings, ObjectProvider<MiniWechatIdentityPort> wechat,
            ObjectProvider<MiniSmsPort> sms, HttpServletRequest request,
            @Value("${fams.mini.binding-support-text:}") String supportText) {
        this.bindings = bindings;
        this.wechat = wechat;
        this.sms = sms;
        this.request = request;
        if (supportText.length() > 500) {
            throw new IllegalArgumentException("Binding support text is too long");
        }
        this.supportText = supportText;
    }

    @Override
    public ResponseEntity<MiniBindingSupport> miniBindingSupport() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MiniBindingSupport(supportText));
    }

    @Override
    public ResponseEntity<MiniBindingChallenge> miniIssueBindingChallenge(MiniBindingChallengeRequest input) {
        MiniSmsPort sender = sms.getIfAvailable();
        if (sender == null) {
            throw smsUnavailable();
        }
        var requestId = AuthRequestContext.requestId(request);
        var delivery = bindings.prepare(input.getEmployeeNo(), input.getPhone(), input.getDeviceId().toString(),
                input.getAction().getValue(), exchange(input.getCode()), requestId);
        try {
            sender.sendBindingCode(delivery.phone(), delivery.otp());
        } catch (RuntimeException exception) {
            bindings.deliveryResult(delivery.challengeToken(), false, requestId);
            throw smsUnavailable();
        }
        bindings.deliveryResult(delivery.challengeToken(), true, requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MiniBindingChallenge(
                delivery.challengeToken(), delivery.expiresAt().atOffset(ZoneOffset.UTC), 60));
    }

    @Override
    public ResponseEntity<MiniBindingResult> miniVerifyBinding(String key, Long version, MiniBindingVerifyRequest input) {
        if (version != 0) {
            throw AuthOperationException.versionConflict();
        }
        var result = bindings.bind(input.getChallengeToken(), input.getDeviceId().toString(), input.getOtp(), key,
                exchange(input.getCode()), AuthRequestContext.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new MiniBindingResult(result.accountId(), result.bindingId(), result.bindingVersion()));
    }

    @Override
    public ResponseEntity<Void> miniUnbind(String key, Long version, MiniUnbindRequest input) {
        var current = (MiniSessionService.Current) request.getAttribute(MiniAuthController.CURRENT);
        requireReason(input);
        bindings.unbind(current.account().id(), current.account().id(), Long.parseLong(current.bindingId()), version,
                key, input.getReason().strip(), "SELF_SESSION", request.getHeader(MiniAuthController.HEADER), null,
                AuthRequestContext.requestId(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> adminMiniUnbind(String accountId, String key, Long version,
            String csrf, MiniUnbindRequest input) {
        AuthAccount actor = AuthRequestContext.account(request);
        if (!actor.roles().contains(Role.SYSTEM_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        requireReason(input);
        long target;
        try {
            target = Long.parseLong(accountId);
            if (target <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw AuthOperationException.accountNotFound();
        }
        bindings.unbind(actor.id(), target, null, version, key, input.getReason().strip(),
                input.getVerificationMethod().strip(), null, AuthRequestContext.session(request),
                AuthRequestContext.requestId(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void requireReason(MiniUnbindRequest input) {
        if (input.getReason().isBlank() || input.getVerificationMethod().isBlank()) {
            throw new AuthOperationException("INVALID_REQUEST", "Reason and verification method are required.", 400);
        }
    }

    private MiniWechatIdentityPort.Identity exchange(String code) {
        MiniWechatIdentityPort adapter = wechat.getIfAvailable();
        if (adapter == null || code.isBlank()) {
            throw wechatUnavailable();
        }
        try {
            MiniWechatIdentityPort.Identity identity = adapter.exchange(code);
            if (identity == null) {
                throw wechatUnavailable();
            }
            return identity;
        } catch (RuntimeException exception) {
            throw wechatUnavailable();
        }
    }

    private AuthOperationException smsUnavailable() {
        return new AuthOperationException("MINI_SMS_UNAVAILABLE", "SMS verification is unavailable.", 503);
    }

    private AuthOperationException wechatUnavailable() {
        return new AuthOperationException("MINI_WECHAT_UNAVAILABLE", "WeChat verification is unavailable.", 503);
    }
}
