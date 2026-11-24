package com.fams.loss;

import com.fams.asset.AssetHandoverPort;
import com.fams.attachment.AttachmentAccessFailure;
import com.fams.loan.LoanRepairPort;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Order(-3)
@RestControllerAdvice(assignableTypes = {LossController.class, LossPhotoController.class})
public class LossErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LossErrorHandler.class);

    @ExceptionHandler(LossFailure.class)
    ResponseEntity<ErrorResponse> failure(LossFailure failure, HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(
            AssetHandoverPort.Failure failure, HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(com.fams.loan.LoanLossPort.Failure.class)
    ResponseEntity<ErrorResponse> recoveryLoan(
            com.fams.loan.LoanLossPort.Failure failure, HttpServletRequest request) {
        log(409, failure.getMessage(), request, failure);
        return response(409, failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(com.fams.repair.LossRepairPort.Failure.class)
    ResponseEntity<ErrorResponse> repair(
            com.fams.repair.LossRepairPort.Failure failure, HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(com.fams.repair.RepairFailure.class)
    ResponseEntity<ErrorResponse> factorySource(com.fams.repair.RepairFailure failure,
            HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(com.fams.loan.LossReturnPort.Failure.class)
    ResponseEntity<ErrorResponse> returned(com.fams.loan.LossReturnPort.Failure failure,
            HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(com.fams.transfer.LossTransferPort.Failure.class)
    ResponseEntity<ErrorResponse> transfer(com.fams.transfer.LossTransferPort.Failure failure,
            HttpServletRequest request) {
        log(failure.status(), failure.getMessage(), request, failure);
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(LoanRepairPort.Failure.class)
    ResponseEntity<ErrorResponse> loan(LoanRepairPort.Failure failure, HttpServletRequest request) {
        log(409, failure.getMessage(), request, failure);
        return response(409, failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(AttachmentAccessFailure.class)
    ResponseEntity<ErrorResponse> attachment(
            AttachmentAccessFailure failure, HttpServletRequest request) {
        log(failure.status(), failure.code(), request, failure);
        return response(failure.status(), failure.code(), Map.of(), request);
    }

    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    ResponseEntity<ErrorResponse> race(HttpServletRequest request) {
        LOG.warn("Loss write conflict: path={}, code=LOSS_WRITE_CONFLICT", request.getRequestURI());
        return response(409, "LOSS_WRITE_CONFLICT", Map.of(), request);
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ErrorResponse> storage(HttpServletRequest request) {
        LOG.error("Loss photo storage unavailable: path={}, code=LOSS_PHOTO_STORAGE_UNAVAILABLE",
                request.getRequestURI());
        return response(503, "LOSS_PHOTO_STORAGE_UNAVAILABLE", Map.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(HttpServletRequest request) {
        LOG.warn("Loss photo or input invalid: path={}, code=LOSS_PHOTO_OR_INPUT_INVALID",
                request.getRequestURI());
        return response(400, "LOSS_PHOTO_OR_INPUT_INVALID", Map.of(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
        LOG.warn("Loss photo too large: path={}, code=LOSS_PHOTO_TOO_LARGE", request.getRequestURI());
        return response(413, "LOSS_PHOTO_TOO_LARGE", Map.of(), request);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpServletRequest request) {
        LOG.warn("Loss input unreadable: path={}, code=LOSS_INPUT_UNREADABLE", request.getRequestURI());
        return response(400, "LOSS_INPUT_UNREADABLE", Map.of(), request);
    }

    private void log(int status, String code, HttpServletRequest request, Throwable error) {
        if (status >= 500) {
            LOG.error("Loss request failed: path={}, code={}", request.getRequestURI(), code, error);
        } else {
            LOG.warn("Loss request rejected: path={}, code={}", request.getRequestURI(), code);
        }
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, Map<String, String> fields, HttpServletRequest request) {
        UUID id =
                request.getAttribute("fams.request.id") instanceof UUID value
                        ? value
                        : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, fields));
    }
}
