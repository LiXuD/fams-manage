package com.fams.loan;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
class AssetReturnUnavailabilityController {
    record Input(@NotNull @Min(0) Long ownershipVersion, @NotBlank @Size(max = 1000) String reason) {}

    private final AssetReturnReassignmentService service;
    private final RequisitionActorContext actors;

    AssetReturnUnavailabilityController(AssetReturnReassignmentService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping({"/api/v1/asset-returns/{receiptId}/reassignment/unavailable",
            "/api/v1/mini/asset-returns/{receiptId}/reassignment/unavailable"})
    LoanViews.AssetReturnDetail declareUnavailable(@PathVariable @Min(1) long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody Input input) {
        return service.declareUnavailable(actors.capture(), receiptId, version, key, input);
    }
}
