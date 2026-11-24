package com.fams.repair;

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
@RestControllerAdvice(
        assignableTypes = {
            RepairController.class,
            RepairReassignmentController.class,
            RepairPhotoController.class,
            RepairFactoryController.class,
            RepairFactoryEvidenceController.class,
            RepairFactoryReplacementReferenceController.class,
            RepairCollaborationController.class,
            RepairCollaborationPhotoController.class
        })
public class RepairErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RepairErrorHandler.class);

    @ExceptionHandler(com.fams.inbound.FactoryReplacementInboundPort.Failure.class)
    ResponseEntity<ErrorResponse> replacementInbound(
            com.fams.inbound.FactoryReplacementInboundPort.Failure failure, HttpServletRequest request) {
        if (failure.status() >= 500) {
            LOG.error("Repair factory replacement failed: path={} code={} status={}",
                    request.getRequestURI(), failure.getMessage(), failure.status(), failure);
        } else {
            LOG.warn("Repair factory replacement rejected: path={} code={} status={}",
                    request.getRequestURI(), failure.getMessage(), failure.status());
        }
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(RepairFailure.class)
    ResponseEntity<ErrorResponse> failure(RepairFailure failure, HttpServletRequest request) {
        if (failure.status() >= 500) {
            LOG.error("Repair request failed: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status(), failure);
        } else {
            LOG.warn("Repair request rejected: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status());
        }
        return response(
                failure.status(),
                failure.getMessage(),
                failure instanceof RepairConflict conflict ? conflict.fields() : Map.of(),
                request);
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(
            AssetHandoverPort.Failure failure, HttpServletRequest request) {
        if (failure.status() >= 500) {
            LOG.error("Repair asset handover failed: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status(), failure);
        } else {
            LOG.warn("Repair asset handover rejected: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status());
        }
        return response(failure.status(), failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(LoanRepairPort.Failure.class)
    ResponseEntity<ErrorResponse> loan(LoanRepairPort.Failure failure, HttpServletRequest request) {
        LOG.warn("Repair loan state conflict: path={} code={}",
                request.getRequestURI(), failure.getMessage());
        return response(409, failure.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(AttachmentAccessFailure.class)
    ResponseEntity<ErrorResponse> attachment(
            AttachmentAccessFailure failure, HttpServletRequest request) {
        LOG.warn("Repair attachment access rejected: path={} code={} status={}",
                request.getRequestURI(), failure.code(), failure.status());
        return response(failure.status(), failure.code(), Map.of(), request);
    }

    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    ResponseEntity<ErrorResponse> race(HttpServletRequest request) {
        LOG.warn("Repair write conflict: path={}", request.getRequestURI());
        return response(409, "REPAIR_WRITE_CONFLICT", Map.of(), request);
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ErrorResponse> storage(HttpServletRequest request) {
        LOG.error("Repair photo storage unavailable: path={}", request.getRequestURI());
        return response(503, "REPAIR_PHOTO_STORAGE_UNAVAILABLE", Map.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(HttpServletRequest request) {
        LOG.warn("Repair photo or input rejected: path={}", request.getRequestURI());
        return response(400, "REPAIR_PHOTO_OR_INPUT_INVALID", Map.of(), request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
        LOG.warn("Repair photo upload exceeded the size limit: path={}", request.getRequestURI());
        return response(413, "REPAIR_PHOTO_TOO_LARGE", Map.of(), request);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpServletRequest request) {
        LOG.warn("Repair request body unreadable: path={}", request.getRequestURI());
        return response(400, "REPAIR_INPUT_UNREADABLE", Map.of(), request);
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
