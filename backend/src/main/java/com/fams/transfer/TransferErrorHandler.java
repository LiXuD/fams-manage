package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(TransferErrorHandler.class);

    @ExceptionHandler(TransferFailure.class)
    ResponseEntity<ErrorResponse> failure(TransferFailure failure, HttpServletRequest request) {
        LOG.warn("Transfer request rejected: path={} code={} status={}", request.getRequestURI(),
                failure.getMessage(), failure.status());
        return reply(failure.status(), failure.getMessage(), request);
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(
            AssetHandoverPort.Failure failure, HttpServletRequest request) {
        if (failure.status() >= 500) {
            LOG.error("Transfer asset handover failed: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status(), failure);
        } else {
            LOG.warn("Transfer asset handover rejected: path={} code={} status={}",
                    request.getRequestURI(), failure.getMessage(), failure.status());
        }
        return reply(failure.status(), failure.getMessage(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(HttpServletRequest request) {
        LOG.warn("Transfer write conflict: path={}", request.getRequestURI());
        return reply(409, "TRANSFER_WRITE_CONFLICT", request);
    }

    private ResponseEntity<ErrorResponse> reply(
            int status, String code, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID id = value instanceof UUID existing ? existing : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
