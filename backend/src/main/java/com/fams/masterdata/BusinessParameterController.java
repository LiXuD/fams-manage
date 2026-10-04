package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.BusinessParameterVersion;
import com.fams.product.contract.model.PageBusinessParameterVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/business-parameters")
class BusinessParameterController {

    private final BusinessParameterService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    BusinessParameterController(BusinessParameterService service,
            MasterDataActorContext actorContext, HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<PageBusinessParameterVersion> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.list(page, pageSize));
    }

    @GetMapping("/current")
    ResponseEntity<BusinessParameterVersion> current() {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.current());
    }

    @GetMapping("/{parameterVersionId}")
    ResponseEntity<BusinessParameterVersion> get(@PathVariable String parameterVersionId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(parameterVersionId)));
    }

    @PostMapping
    ResponseEntity<BusinessParameterVersion> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @RequestBody JsonNode body) {
        long actorId = actorContext.requireSystemAdministrator();
        BusinessParameterVersion result = service.create(actorId, expectedVersion,
                body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The business-parameter version");
        }
    }
}
