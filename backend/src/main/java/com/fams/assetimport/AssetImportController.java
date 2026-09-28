package com.fams.assetimport;

import com.fams.auth.AssetActorContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/asset-import-batches")
class AssetImportController {
    record MappingRequest(Map<String, String> mapping) { }

    private final AssetImportBatchService batches;
    private final AssetActorContext actors;

    AssetImportController(AssetImportBatchService batches, AssetActorContext actors) {
        this.batches = batches;
        this.actors = actors;
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template() {
        actors.requireWarehouseOperator();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fams-assets.xlsx\"")
                .body(AssetImportWorkbook.template());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AssetImportBatchService.Ticket> upload(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestPart("file") MultipartFile file) {
        try {
            var ticket = batches.upload(actors.requireWarehouseOperator().id(), key, file.getBytes());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(HttpHeaders.LOCATION, "/api/v1/asset-import-batches/" + ticket.id())
                    .body(ticket);
        } catch (IOException failure) {
            throw new IllegalArgumentException("ASSET_IMPORT_INVALID_XLSX", failure);
        }
    }

    @PostMapping("/{batchId}/preview")
    ResponseEntity<AssetImportBatchService.Preview> preview(@PathVariable UUID batchId,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody(required = false) MappingRequest request) {
        Map<String, String> mapping = request == null || request.mapping() == null
                ? Map.of() : request.mapping();
        return ResponseEntity.ok(batches.preview(actors.requireWarehouseOperator().id(), batchId,
                version, mapping));
    }

    @GetMapping("/{batchId}")
    ResponseEntity<AssetImportBatchService.Ticket> get(@PathVariable UUID batchId) {
        return ResponseEntity.ok(batches.get(actors.requireWarehouseOperator().id(), batchId));
    }

    @GetMapping("/{batchId}/rows")
    ResponseEntity<AssetImportBatchService.RowPage> rows(@PathVariable UUID batchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        return ResponseEntity.ok(batches.rows(actors.requireWarehouseOperator().id(), batchId,
                status, page, pageSize));
    }
}
