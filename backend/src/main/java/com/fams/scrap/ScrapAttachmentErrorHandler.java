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

/**
 * Translates scrap attachment refusals into the contract {@code ErrorResponse} shape, and is bound to
 * {@link ScrapAttachmentController} alone through {@code assignableTypes} so it never rewrites the
 * scrap request endpoints handled by {@link ScrapController}. The {@code @Order(-3)} places it ahead
 * of the generic advices, which matters because attachment upload and download failures would
 * otherwise be reported by a broader handler that knows nothing about the attachment error codes.
 *
 * <p>Two failure families keep their own status and code: {@link AssetHandoverPort.Failure}, raised by
 * the asset-side preconditions (unknown request, stale version, missing role) carries both, and
 * {@link AttachmentAccessFailure}, raised by the shared attachment intake and reader, likewise. Status
 * 500 and above on the handover failure is logged at error level with the stack trace, everything
 * below at warn level without it, because a client-caused rejection is not an incident.
 *
 * <p>The remaining handlers fix the contract for conditions the shared code cannot classify: any
 * {@code IOException} from quarantine storage becomes 503 {@code SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE},
 * {@code IllegalArgumentException} and path/argument type mismatches become 400
 * {@code SCRAP_ATTACHMENT_INVALID}, an oversized part becomes 413 {@code SCRAP_ATTACHMENT_TOO_LARGE},
 * and a lost pessimistic lock race becomes 409 {@code SCRAP_ATTACHMENT_WRITE_CONFLICT}. Every body
 * repeats the code in the message field and reuses the {@code fams.request.id} request attribute when
 * one was assigned, so the client can correlate the response with the server log line.
 */
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
