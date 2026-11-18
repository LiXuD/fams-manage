package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping("/api/v1/messages/reminders/{id}/follow-up")
class ReminderFollowupController {
    private final ReminderFollowupService followups;
    private final RequisitionActorContext actors;

    ReminderFollowupController(ReminderFollowupService followups, RequisitionActorContext actors) {
        this.followups = followups;
        this.actors = actors;
    }

    @GetMapping
    ReminderFollowupService.View get(@PathVariable @Min(1) long id) {
        return followups.get(actors.capture(), id);
    }

    @PostMapping
    ReminderFollowupService.Followup record(@PathVariable @Min(1) long id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ReminderFollowupService.Input input) {
        return followups.record(actors.capture(), id, key, input);
    }

    @ExceptionHandler(ReminderFailure.class)
    ResponseEntity<ErrorResponse> rejection(ReminderFailure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }
}
