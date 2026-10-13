package com.fams.requisition;

import com.fams.asset.AssetHandoverPort;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Order(-1)
@RestControllerAdvice(assignableTypes = {RequisitionHandoverController.class, RequisitionProxyConfirmationController.class, ItemTargetChangeController.class})
class HandoverErrorHandler {
    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> failure(
            AssetHandoverPort.Failure error, HttpServletRequest request) {
        return ResponseEntity.status(error.status())
                .body(
                        new ErrorResponse(
                                error.getMessage(),
                                error.getMessage(),
                                request.getAttribute("fams.request.id") instanceof UUID id
                                        ? id
                                        : UUID.randomUUID(),
                                Map.of()));
    }
}
