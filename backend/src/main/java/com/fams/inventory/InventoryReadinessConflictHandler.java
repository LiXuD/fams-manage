package com.fams.inventory;

import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@Profile("fams-product")
@Order(-3)
@RestControllerAdvice(assignableTypes = InventoryReadinessController.class)
class InventoryReadinessConflictHandler {
    @ExceptionHandler(InventoryReviewConflict.class)
    ResponseEntity<ErrorResponse> conflict(
            InventoryReviewConflict failure, HttpServletRequest request) {
        UUID id =
                request.getAttribute("fams.request.id") instanceof UUID value
                        ? value
                        : UUID.randomUUID();
        return ResponseEntity.status(409)
                .body(
                        new ErrorResponse(
                                failure.getMessage(), failure.getMessage(), id, failure.fields()));
    }
}
