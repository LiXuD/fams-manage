package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.attachment.AttachmentAccessFailure;
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
@RestControllerAdvice(assignableTypes = ScrapAttachmentController.class)
public class ScrapAttachmentErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapAttachmentErrorHandler.class);

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> asset(AssetHandoverPort.Failure error, HttpServletRequest request) {
        if (error.status() >= 500) {
            LOG.error("Scrap attachment asset handover failed: path={} code={} status={}",
                    request.getRequestURI(), error.getMessage(), error.status(), error);
        } else {
            LOG.warn("Scrap attachment asset handover rejected: path={} code={} status={}",
                    request.getRequestURI(), error.getMessage(), error.status());
        }
        return response(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler(AttachmentAccessFailure.class)
    ResponseEntity<ErrorResponse> attachment(AttachmentAccessFailure error, HttpServletRequest request) {
        LOG.warn("Scrap attachment access rejected: path={} code={} status={}",
                request.getRequestURI(), error.code(), error.status());
        return response(error.status(), error.code(), request);
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ErrorResponse> storage(HttpServletRequest request) {
        LOG.error("Scrap attachment storage unavailable: path={}", request.getRequestURI());
        return response(503, "SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE", request);
    }

    @ExceptionHandler({IllegalArgumentException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid(HttpServletRequest request) {
        LOG.warn("Scrap attachment request rejected: path={}", request.getRequestURI());
        return response(400, "SCRAP_ATTACHMENT_INVALID", request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
        LOG.warn("Scrap attachment upload exceeded the size limit: path={}", request.getRequestURI());
        return response(413, "SCRAP_ATTACHMENT_TOO_LARGE", request);
    }

    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    ResponseEntity<ErrorResponse> race(HttpServletRequest request) {
        LOG.warn("Scrap attachment write conflict: path={}", request.getRequestURI());
        return response(409, "SCRAP_ATTACHMENT_WRITE_CONFLICT", request);
    }

    private ResponseEntity<ErrorResponse> response(int status, String code, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
