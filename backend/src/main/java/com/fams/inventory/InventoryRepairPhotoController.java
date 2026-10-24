package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@RequestMapping({"/api/v1/inventory-repair-evidence", "/api/v1/mini/inventory-repair-evidence"})
class InventoryRepairPhotoController {
    private final InventoryRepairPhotoReadService reads;
    private final RequisitionActorContext actors;

    InventoryRepairPhotoController(
            InventoryRepairPhotoReadService reads, RequisitionActorContext actors) {
        this.reads = reads;
        this.actors = actors;
    }

    @PostMapping("/{repair}/observations/{observation}/photos/{object}/download-grant")
    Map<String, Object> grant(
            @PathVariable @Positive long repair,
            @PathVariable @Positive long observation,
            @PathVariable UUID object,
            jakarta.servlet.http.HttpServletRequest request) {
        var grant = reads.grant(actors.capture(), repair, observation, object);
        String prefix =
                request.getRequestURI().startsWith("/api/v1/mini/") ? "/api/v1/mini" : "/api/v1";
        return Map.of(
                "url",
                prefix
                        + "/inventory-repair-evidence/"
                        + repair
                        + "/observations/"
                        + observation
                        + "/photos/"
                        + object
                        + "/content?grant="
                        + grant.token(),
                "expiresAtEpochSecond",
                grant.expiresAtEpochSecond());
    }

    @GetMapping("/{repair}/observations/{observation}/photos")
    Map<String, Object> list(
            @PathVariable @Positive long repair, @PathVariable @Positive long observation) {
        return Map.of("items", reads.list(actors.capture(), repair, observation));
    }

    @GetMapping("/{repair}/observations/{observation}/photos/{object}/content")
    ResponseEntity<byte[]> content(
            @PathVariable @Positive long repair,
            @PathVariable @Positive long observation,
            @PathVariable UUID object,
            @RequestParam("grant") @Size(max = 256) String token)
            throws IOException {
        var content = reads.read(actors.capture(), repair, observation, object, token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mime()))
                .contentLength(content.bytes().length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(content.bytes());
    }
}
