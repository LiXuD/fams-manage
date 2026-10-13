package com.fams.requisition;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
class MiniProxyAttachmentReadController {
    private final AttachmentReadService reads;

    MiniProxyAttachmentReadController(AttachmentReadService reads) { this.reads = reads; }

    @PostMapping("/api/v1/mini/attachments/{objectId}/download-grant")
    Map<String, Object> grant(@PathVariable UUID objectId) {
        var result = reads.grant(objectId);
        return Map.of("url", result.get("url").toString().replace("/api/v1/attachments/", "/api/v1/mini/attachments/"),
                "expiresAtEpochSecond", result.get("expiresAtEpochSecond"));
    }

    @GetMapping("/api/v1/mini/attachments/{objectId}/content")
    ResponseEntity<byte[]> content(@PathVariable UUID objectId, @RequestParam("grant") String grant) throws IOException {
        var content = reads.read(objectId, grant);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mime()))
                .contentLength(content.bytes().length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }
}
