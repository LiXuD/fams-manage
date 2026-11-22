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

@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class InboundAttachmentController {
    private final AttachmentUploadService uploads;
    private final AttachmentUploadKeys keys;
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
