package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reading the inbox never changes a business state, so the same pair serves PC and mini sessions. */
@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/messages", "/api/v1/mini/messages"})
class MessageController {
    private final MessageService messages;
    private final RequisitionActorContext actors;

    MessageController(MessageService messages, RequisitionActorContext actors) {
        this.messages = messages;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "INBOX") @Pattern(regexp = "INBOX|EXCEPTION") String view,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return messages.list(actors.capture(), view, page, pageSize);
    }

    @PostMapping("/{kind}/{id}/read")
    Map<String, Object> read(@PathVariable @Pattern(regexp = "TODO|REMINDER") String kind,
            @PathVariable @Min(1) long id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return messages.read(actors.capture(), kind, id);
    }

    @ExceptionHandler(MessageFailure.class)
    ResponseEntity<ErrorResponse> rejection(MessageFailure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }
}
