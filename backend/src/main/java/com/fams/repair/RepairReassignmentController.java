package com.fams.repair;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/** One administrator transfers procedural ownership, never physical custody. */
@Profile("fams-product")
@Validated
@RestController
@RequestMapping({"/api/v1/repairs", "/api/v1/mini/repairs"})
public class RepairReassignmentController {
    public record Input(
            @Positive long processorAccountId,
            @NotNull JsonNode snapshot,
            boolean unsynchronizedResultsChecked,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    private final RepairService repairs;
    private final RequisitionActorContext actors;

    public RepairReassignmentController(RepairService repairs, RequisitionActorContext actors) {
        this.repairs = repairs;
        this.actors = actors;
    }

    @GetMapping("/{id}/reassignment")
    Map<String, Object> inspect(@PathVariable @Positive long id) {
        return repairs.reassignment(actors.capture(), id);
    }

    @PostMapping("/{id}/reassignment")
    Map<String, Object> reassign(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Input input) {
        var actor = actors.capture();
        for (int attempt = 0; ; attempt++) {
            try {
                return repairs.reassign(actor, id, version, key, input);
            } catch (org.springframework.dao.CannotAcquireLockException conflict) {
                if (attempt >= 1) {
                    throw conflict;
                }
            }
        }
    }
}
