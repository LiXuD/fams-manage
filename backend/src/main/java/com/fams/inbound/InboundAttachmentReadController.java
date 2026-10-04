package com.fams.inbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fams.attachment.AttachmentReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class InboundAttachmentReadController {
    private final AttachmentReadService service;
    public InboundAttachmentReadController(AttachmentReadService service) { this.service = service; }
    @GetMapping("/api/v1/inbound-receipts/{receiptId}/attachments")
    Map<String, List<Map<String, Object>>> list(@PathVariable long receiptId) {
        return Map.of("items", service.list(receiptId));
    }
    @PostMapping("/api/v1/attachments/{objectId}/download-grant")
    Map<String, Object> grant(@PathVariable UUID objectId) { return service.grant(objectId); }
    @GetMapping("/api/v1/attachments/{objectId}/content")
    ResponseEntity<byte[]> content(@PathVariable UUID objectId, @RequestParam("grant") String grant) throws IOException {
        var content = service.read(objectId, grant);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mime()))
                .contentLength(content.bytes().length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }
}
