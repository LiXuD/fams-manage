package com.fams.loan;

import java.util.List;
import java.util.Map;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/asset-returns/{receiptId}/reassignment")
class AssetReturnReassignmentController {
    record Item(@Min(1) long id, @Min(0) long version, @NotBlank String status,
            @Min(0) long assetVersion, @Min(0) long lockVersion) {}
    record Reassignment(@Min(1) long processorAccountId, @Min(0) long ownershipVersion,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 1000) String evidence,
            @NotNull Boolean unsynchronizedResultsChecked,
            @NotNull @Size(min = 1, max = 1) List<@Valid Item> items) {}

    private final AssetReturnReassignmentService service;
    private final RequisitionActorContext actors;

    AssetReturnReassignmentController(AssetReturnReassignmentService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> inspect(@PathVariable long receiptId) {
        return service.inspect(actors.capture(), receiptId);
    }

    @PostMapping
    Map<String, Object> reassign(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody Reassignment input) {
        return service.reassign(actors.capture(), receiptId, version, key, input);
    }
}
