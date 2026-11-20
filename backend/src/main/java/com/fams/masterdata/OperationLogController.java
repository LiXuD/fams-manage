package com.fams.masterdata;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.fams.product.contract.model.PageOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/operation-logs")
class OperationLogController {

    private final OperationLogService service;

    OperationLogController(OperationLogService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<PageOperationLog> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String actorAccountId,
            @RequestParam(required = false) String occurredFrom,
            @RequestParam(required = false) String occurredTo) {
        return ResponseEntity.ok(service.list(page, pageSize, source, actorAccountId,
                occurredFrom, occurredTo));
    }
}
