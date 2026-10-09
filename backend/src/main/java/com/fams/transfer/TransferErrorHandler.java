package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = TransferController.class)
@Order(-100)
class TransferErrorHandler {
    @ExceptionHandler(TransferFailure.class)
    ResponseEntity<ErrorResponse> failure(TransferFailure failure, HttpServletRequest request) {
        return reply(failure.status(), failure.getMessage(), request);
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(
            AssetHandoverPort.Failure failure, HttpServletRequest request) {
        return reply(failure.status(), failure.getMessage(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(HttpServletRequest request) {
        return reply(409, "TRANSFER_WRITE_CONFLICT", request);
    }

    private ResponseEntity<ErrorResponse> reply(
            int status, String code, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID id = value instanceof UUID existing ? existing : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
