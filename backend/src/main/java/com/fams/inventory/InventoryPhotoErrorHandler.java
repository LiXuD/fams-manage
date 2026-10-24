package com.fams.inventory;

import com.fams.attachment.AttachmentAccessFailure;
import com.fams.attachment.AttachmentUploadConflict;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Order(-3)
@RestControllerAdvice(
        assignableTypes = {InventoryPhotoController.class, InventoryRepairPhotoController.class})
final class InventoryPhotoErrorHandler {
    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    ResponseEntity<ErrorResponse> badParameters(Exception error, HttpServletRequest request) {
        return response(
                400,
                "INVALID_ATTACHMENT_REQUEST",
                "Attachment request parameters are invalid",
                request);
    }

    @ExceptionHandler(AttachmentAccessFailure.class)
    ResponseEntity<ErrorResponse> access(
            AttachmentAccessFailure error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), request);
    }

    @ExceptionHandler(AttachmentUploadConflict.class)
    ResponseEntity<ErrorResponse> conflict(
            AttachmentUploadConflict error, HttpServletRequest request) {
        return response(409, "ATTACHMENT_IDEMPOTENCY_CONFLICT", error.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(
            IllegalArgumentException error, HttpServletRequest request) {
        return response(
                400,
                "INVALID_ATTACHMENT",
                "Attachment format, metadata or quota is invalid",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(
            MaxUploadSizeExceededException error, HttpServletRequest request) {
        return response(
                413, "ATTACHMENT_TOO_LARGE", "Attachment request exceeds the size limit", request);
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> unavailable(IOException error, HttpServletRequest request) {
        return response(
                503, "ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment could not be stored", request);
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, String message, HttpServletRequest request) {
        var id = request.getAttribute("fams.request.id");
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                code,
                                message,
                                id instanceof UUID uuid ? uuid : UUID.randomUUID(),
                                Map.of()));
    }
}
