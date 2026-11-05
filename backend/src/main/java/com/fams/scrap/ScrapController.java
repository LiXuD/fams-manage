package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@RestController
@RequestMapping({"/api/v1/scrap-requests", "/api/v1/mini/scrap-requests"})
public class ScrapController {
    private final ScrapService scrap;
    private final RequisitionActorContext actors;

    public ScrapController(ScrapService scrap, RequisitionActorContext actors) {
        this.scrap = scrap;
        this.actors = actors;
    }

    @PostMapping
    ScrapService.Receipt submit(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapService.Submission input) {
        return scrap.submit(actors.capture(), key, input);
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> rejection(AssetHandoverPort.Failure failure, HttpServletRequest request) {
        UUID requestId = request.getAttribute("fams.request.id") instanceof UUID id ? id : UUID.randomUUID();
        String code = failure.getMessage();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(code, code, requestId, Map.of()));
    }
}
