package com.fams.inbound;

import java.util.Map;

import com.fams.auth.InboundActorContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/inbound-receipts")
class InboundController {
    private final InboundService service;
    private final InboundActorContext actors;

    InboundController(InboundService service, InboundActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String receiptNumber) {
        return service.list(actors.requireReader(), page, pageSize, status, receiptNumber);
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(actors.requireWarehouse(), key, body));
    }

    @GetMapping("/{receiptId}")
    Map<String, Object> get(@PathVariable long receiptId) {
        return service.get(actors.requireReader(), receiptId);
    }

    @PatchMapping("/{receiptId}")
    Map<String, Object> update(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody Map<String, Object> body) {
        return service.update(actors.requireWarehouse(), receiptId, version, key, body);
    }

    @PostMapping("/{receiptId}/submit")
    Map<String, Object> submit(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version) {
        return service.submit(actors.requireWarehouse(), receiptId, version, key);
    }

    @PostMapping("/{receiptId}/cancel")
    Map<String, Object> cancel(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody Map<String, Object> body) {
        return service.cancel(actors.requireWarehouse(), receiptId, version, key, body);
    }

    @PostMapping("/{receiptId}/decision")
    Map<String, Object> decision(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody Map<String, Object> body) {
        return service.decide(actors.requireAdmin(), receiptId, version, key, body);
    }

    @PostMapping("/{receiptId}/claim")
    Map<String, Object> claim(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version) {
        return service.claim(actors.requireWarehouse(), receiptId, version, key);
    }

    @PostMapping("/{receiptId}/items/{itemId}/accept")
    Map<String, Object> accept(@PathVariable long receiptId, @PathVariable long itemId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody Map<String, Object> body) {
        return service.accept(actors.requireWarehouse(), receiptId, itemId, version, key, body);
    }
}
