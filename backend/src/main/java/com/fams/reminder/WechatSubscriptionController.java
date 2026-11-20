package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;
import com.fams.product.contract.model.MiniSubscriptionVisibility;
import com.fams.product.contract.model.WechatSubscriptionGrant;
import com.fams.product.contract.model.WechatSubscriptionGrantRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@Validated
class WechatSubscriptionController {
    private final WechatSubscriptionService subscriptions;
    private final RequisitionActorContext actors;

    WechatSubscriptionController(WechatSubscriptionService subscriptions, RequisitionActorContext actors) {
        this.subscriptions = subscriptions;
        this.actors = actors;
    }

    @GetMapping("/api/v1/mini/subscription-visibility")
    MiniSubscriptionVisibility visibility() {
        return subscriptions.visibility(actors.capture());
    }

    @PostMapping("/api/v1/mini/subscription-grants")
    ResponseEntity<WechatSubscriptionGrant> register(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody WechatSubscriptionGrantRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptions.register(actors.capture(), key, body));
    }

    @ExceptionHandler(ReminderFailure.class)
    ResponseEntity<ErrorResponse> rejection(ReminderFailure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }
}
