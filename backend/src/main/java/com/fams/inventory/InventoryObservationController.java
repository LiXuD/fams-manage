package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Validated
@RestController
@Profile("fams-product")
@RequestMapping({"/api/v1/inventory-observations", "/api/v1/mini/inventory-observations"})
class InventoryObservationController {
    record Lookup(
            @jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Pattern(regexp = "SCAN|CODE")
                    String source,
            @jakarta.validation.constraints.NotBlank @Size(max = 256) String value) {}

    private final InventoryObservationService service;
    private final RequisitionActorContext actors;

    InventoryObservationController(
            InventoryObservationService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/calibrations")
    Map<String, Object> calibrate(@Valid @RequestBody InventoryObservationInput.Calibration input) {
        return service.calibrate(actors.capture(), input);
    }

    @GetMapping("/tasks/{taskId}/identification")
    Map<String, Object> identify(
            @PathVariable @Positive long taskId,
            @RequestParam String source,
            @RequestParam @Size(min = 1, max = 256) String value) {
        return service.identify(actors.capture(), taskId, source, value);
    }

    @PostMapping("/tasks/{taskId}/identification")
    Map<String, Object> identifyBody(
            @PathVariable @Positive long taskId, @Valid @RequestBody Lookup input) {
        return service.identify(actors.capture(), taskId, input.source(), input.value());
    }

    @PostMapping("/tasks/{taskId}/events")
    Map<String, Object> submit(
            @PathVariable @Positive long taskId,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key")
                    java.util.UUID key,
            @org.springframework.web.bind.annotation.RequestHeader("If-Match-Version")
                    @jakarta.validation.constraints.PositiveOrZero
                    long assignmentVersion,
            @Valid @RequestBody InventoryObservationInput.Observation input) {
        if (!key.equals(input.eventId()) || assignmentVersion != input.assignmentVersion()) {
            throw new InventoryFailure(409, "INVENTORY_EVENT_REQUEST_IDENTITY");
        }
        return service.submit(actors.capture(), taskId, input);
    }
}
