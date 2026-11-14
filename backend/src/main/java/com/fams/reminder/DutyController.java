package com.fams.reminder;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duty contacts are a notification-only configuration, so only the PC console exposes them and only a system
 * administrator may rotate them. Replacement is version-guarded and idempotent so a lost response cannot turn
 * one duty change into two chain rows.
 */
@RestController
@Profile("fams-product")
@Validated
@RequestMapping("/api/v1/duty-assignments")
class DutyController {
    private final DutyService duties;
    private final MasterDataActorContext actors;
    private final HttpServletRequest servletRequest;

    DutyController(DutyService duties, MasterDataActorContext actors, HttpServletRequest servletRequest) {
        this.duties = duties;
        this.actors = actors;
        this.servletRequest = servletRequest;
    }

    public record DutyRequest(
            @Pattern(regexp = "^[0-9]+$") String primaryAccountId,
            @Pattern(regexp = "^[0-9]+$") String backupAccountId) {}

    @GetMapping
    DutyService.DutyAssignmentList list() {
        return duties.current(actors.requireCatalogSelector());
    }

    @PutMapping("/{roleCode}")
    DutyService.DutyAssignment replace(@PathVariable @Pattern(regexp = "ASSET_ADMIN|WAREHOUSE_OPERATOR") String roleCode,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody DutyRequest body) {
        return duties.replace(actors.requireSystemAdministrator(), requestId(), roleCode,
                Long.parseLong(body.primaryAccountId()),
                body.backupAccountId() == null ? null : Long.parseLong(body.backupAccountId()), version, key);
    }

    @ExceptionHandler(ReminderFailure.class)
    ResponseEntity<ErrorResponse> rejection(ReminderFailure failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), requestId(), Map.of()));
    }

    private UUID requestId() {
        return servletRequest.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
    }
}
