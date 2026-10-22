package com.fams.repair;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Profile("fams-product")
@Validated
@RestController
@RequestMapping("/api/v1")
public class RepairCollaborationController {
    private final RepairCollaborationService collaboration;
    private final RequisitionActorContext actors;

    public RepairCollaborationController(
            RepairCollaborationService collaboration, RequisitionActorContext actors) {
        this.collaboration = collaboration;
        this.actors = actors;
    }

    @GetMapping("/repairs/{repair}/collaborations")
    Map<String, Object> board(@PathVariable @Positive long repair) {
        return collaboration.staffBoard(actors.capture(), repair);
    }

    @GetMapping("/repairs/{repair}/eligible-technicians")
    Map<String, Object> technicians(
            @PathVariable @Positive long repair,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return collaboration.technicians(actors.capture(), repair, page, pageSize);
    }

    @PostMapping("/repairs/{repair}/collaborations")
    Map<String, Object> invite(
            @PathVariable @Positive long repair,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairCollaborationInput.Invitation input) {
        var actor = actors.capture();
        return retry(() -> collaboration.invite(actor, repair, version, key, input));
    }

    @PostMapping("/repair-collaborations/{invitation}/revoke")
    Map<String, Object> revoke(
            @PathVariable @Positive long invitation,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> collaboration.revoke(actor, invitation, version, key, input));
    }

    @GetMapping("/repair-collaborations")
    Map<String, Object> mine(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return collaboration.mine(actors.capture(), page, pageSize);
    }

    @GetMapping("/repair-collaborations/{invitation}")
    Map<String, Object> get(@PathVariable @Positive long invitation) {
        return collaboration.get(actors.capture(), invitation);
    }

    @PostMapping("/repair-collaborations/{invitation}/opinions")
    Map<String, Object> opinion(
            @PathVariable @Positive long invitation,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairCollaborationInput.Opinion input) {
        var actor = actors.capture();
        return retry(() -> collaboration.opinion(actor, invitation, version, key, input));
    }

    private Map<String, Object> retry(Supplier<Map<String, Object>> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (org.springframework.dao.CannotAcquireLockException conflict) {
                if (attempt >= 1) {
                    throw conflict;
                }
            }
        }
    }
}
