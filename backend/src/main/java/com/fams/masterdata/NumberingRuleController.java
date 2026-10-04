package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.CatalogStatusChangeRequest;
import com.fams.product.contract.model.NumberingRule;
import com.fams.product.contract.model.NumberingRuleCreateRequest;
import com.fams.product.contract.model.NumberingRuleUpdateRequest;
import com.fams.product.contract.model.PageNumberingRule;
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
@RequestMapping("/api/v1/numbering-rules")
class NumberingRuleController {

    private final NumberingRuleService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    NumberingRuleController(NumberingRuleService service, MasterDataActorContext actorContext,
            HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<PageNumberingRule> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.list(page, pageSize));
    }

    @GetMapping("/{numberingRuleId}")
    ResponseEntity<NumberingRule> get(@PathVariable String numberingRuleId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(numberingRuleId)));
    }

    @PostMapping
    ResponseEntity<NumberingRule> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody NumberingRuleCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        NumberingRule result = service.create(actorId, body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{numberingRuleId}")
    ResponseEntity<NumberingRule> update(
            @PathVariable String numberingRuleId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody NumberingRuleUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.update(actorId, parseId(numberingRuleId), expectedVersion,
                body, key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{numberingRuleId}/disable")
    ResponseEntity<NumberingRule> disable(
            @PathVariable String numberingRuleId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(numberingRuleId),
                expectedVersion, false, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{numberingRuleId}/enable")
    ResponseEntity<NumberingRule> enable(
            @PathVariable String numberingRuleId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(numberingRuleId),
                expectedVersion, true, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The numbering rule");
        }
    }
}
