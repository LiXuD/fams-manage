package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.NotificationChannelConfig;
import com.fams.product.contract.model.NotificationChannelConfigReplaceRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/notification-channels")
class NotificationChannelConfigController {

    private final NotificationChannelConfigService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    NotificationChannelConfigController(NotificationChannelConfigService service,
            MasterDataActorContext actorContext, HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<NotificationChannelConfig> current() {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.current());
    }

    @PostMapping
    ResponseEntity<NotificationChannelConfig> replace(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody NotificationChannelConfigReplaceRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        NotificationChannelConfig result = service.replace(actorId, expectedVersion,
                body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
