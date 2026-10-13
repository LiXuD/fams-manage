package com.fams.requisition;

import com.fams.attachment.AttachmentUploadKeys;
import com.fams.attachment.AttachmentUploadService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class ProxyConfirmationAttachmentController {

    private final AttachmentUploadService uploads;

    private final AttachmentUploadKeys keys;

    private final com.fams.attachment.AttachmentReadService reads;

    public ProxyConfirmationAttachmentController(
            com.fams.attachment.AttachmentWriter writer,
            com.fams.attachment.AttachmentRegistry registry,
            com.fams.auth.AttachmentActorContext actors,
            ProxyConfirmationAttachmentPort business,
            AttachmentUploadKeys keys,
            com.fams.attachment.AttachmentReadService reads) {
        this.uploads = new AttachmentUploadService(writer, registry, actors, business);
        this.keys = keys;
        this.reads = reads;
    }

    @org.springframework.web.bind.annotation.GetMapping(
            {"/api/v1/proxy-confirmations/{receiptId}/attachments", "/api/v1/mini/proxy-confirmations/{receiptId}/attachments"})
    Map<String, Object> list(@PathVariable long receiptId) {
        return Map.of("items", reads.list("PROXY_CONFIRMATION", receiptId));
    }

    @PostMapping(
            value = {"/api/v1/proxy-confirmations/{receiptId}/attachments", "/api/v1/mini/proxy-confirmations/{receiptId}/attachments"},
            consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> upload(
            @PathVariable long receiptId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match-Version") long version,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var source = file.getInputStream()) {
            var result =
                    uploads.uploadIdempotent(
                            keys,
                            key,
                            receiptId,
                            version,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            source);
            return ResponseEntity.status(201)
                    .body(
                            Map.of(
                                    "objectId",
                                    result.file().id().toString(),
                                    "filename",
                                    file.getOriginalFilename(),
                                    "contentType",
                                    result.format().mime(),
                                    "sizeBytes",
                                    result.file().size(),
                                    "scanStatus",
                                    result.scanResult().name(),
                                    "businessVersion",
                                    version + 1));
        }
    }

    @PostMapping(
            value = {"/api/v1/proxy-confirmations/{receiptId}/attachments/{objectId}/corrections", "/api/v1/mini/proxy-confirmations/{receiptId}/attachments/{objectId}/corrections"},
            consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> correct(
            @PathVariable long receiptId,
            @PathVariable java.util.UUID objectId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match-Version") long version,
            @RequestParam("reason") String reason,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var source = file.getInputStream()) {
            var result =
                    uploads.correctIdempotent(
                            keys,
                            key,
                            receiptId,
                            version,
                            objectId,
                            reason,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            source);
            return ResponseEntity.status(201)
                    .body(
                            Map.of(
                                    "objectId",
                                    result.file().id().toString(),
                                    "filename",
                                    file.getOriginalFilename(),
                                    "contentType",
                                    result.format().mime(),
                                    "sizeBytes",
                                    result.file().size(),
                                    "scanStatus",
                                    result.scanResult().name(),
                                    "businessVersion",
                                    version + 1));
        }
    }
}
