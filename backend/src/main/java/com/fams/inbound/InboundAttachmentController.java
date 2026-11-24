package com.fams.inbound;

import java.io.IOException;
import com.fams.attachment.AttachmentUploadKeys;
import com.fams.attachment.AttachmentUploadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart upload endpoints for inbound receipt evidence, active only when the attachment
 * quarantine storage is configured. Both routes are idempotent through the {@code Idempotency-Key}
 * header and guarded by {@code If-Match-Version}; the file stream is consumed inside a
 * try-with-resources so the multipart source is always closed.
 */
@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class InboundAttachmentController {
    private final AttachmentUploadService uploads;
    private final AttachmentUploadKeys keys;
    /**
     * Wires the controller to the shared upload service and the inbound business key mapping.
     *
     * @param uploads attachment upload service performing quarantine, scan and registration
     * @param keys business-type key mapping binding uploads to inbound receipts
     */
    public InboundAttachmentController(AttachmentUploadService uploads, AttachmentUploadKeys keys) {
        this.uploads = uploads;
        this.keys = keys;
    }

    @PostMapping(value = "/api/v1/inbound-receipts/{receiptId}/attachments", consumes = "multipart/form-data")
    ResponseEntity<InboundViews.AttachmentReceipt> upload(@PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match-Version") long version, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var source = file.getInputStream()) {
            var result = uploads.uploadIdempotent(keys, key, receiptId, version,
                    file.getOriginalFilename(), file.getContentType(), source);
            return ResponseEntity.status(201)
                    .body(InboundViews.attachmentReceipt(result, file.getOriginalFilename(), version));
        }
    }

    @PostMapping(value = "/api/v1/inbound-receipts/{receiptId}/attachments/{objectId}/corrections",
            consumes = "multipart/form-data")
    ResponseEntity<InboundViews.AttachmentReceipt> correct(@PathVariable long receiptId, @PathVariable java.util.UUID objectId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match-Version") long version,
            @RequestParam("reason") String reason, @RequestPart("file") MultipartFile file) throws IOException {
        try (var source = file.getInputStream()) {
            var result = uploads.correctIdempotent(keys, key, receiptId, version, objectId, reason,
                    file.getOriginalFilename(), file.getContentType(), source);
            return ResponseEntity.status(201)
                    .body(InboundViews.attachmentReceipt(result, file.getOriginalFilename(), version));
        }
    }

}
