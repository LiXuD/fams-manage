package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.attachment.AttachmentAccessFailure;
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
@Order(-3)
@RestControllerAdvice(assignableTypes = ScrapAttachmentController.class)
public class ScrapAttachmentErrorHandler {
    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(AssetHandoverPort.Failure error, HttpServletRequest request) {
        return response(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler(AttachmentAccessFailure.class)
    ResponseEntity<ErrorResponse> attachment(AttachmentAccessFailure error, HttpServletRequest request) {
        return response(error.status(), error.code(), request);
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ErrorResponse> storage(HttpServletRequest request) {
        return response(503, "SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(HttpServletRequest request) {
        return response(400, "SCRAP_ATTACHMENT_INVALID", request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
        return response(413, "SCRAP_ATTACHMENT_TOO_LARGE", request);
    }

    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    ResponseEntity<ErrorResponse> race(HttpServletRequest request) {
        return response(409, "SCRAP_ATTACHMENT_WRITE_CONFLICT", request);
    }

    private ResponseEntity<ErrorResponse> response(int status, String code, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
