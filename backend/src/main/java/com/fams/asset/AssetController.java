package com.fams.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;

import com.fams.auth.AssetActorContext;
import com.fams.product.contract.model.Asset;
import com.fams.product.contract.model.AssetCancelRequest;
import com.fams.product.contract.model.AssetCopyRequest;
import com.fams.product.contract.model.AssetCopyResult;
import com.fams.product.contract.model.AssetCreateRequest;
import com.fams.product.contract.model.AssetQrAccessResult;
import com.fams.product.contract.model.AssetTag;
import com.fams.product.contract.model.AssetTagBatchPrintJob;
import com.fams.product.contract.model.AssetTagBatchPrintRequest;
import com.fams.product.contract.model.AssetTagPrintJob;
import com.fams.product.contract.model.AssetTagPrintJobResult;
import com.fams.product.contract.model.AssetTagReplaceRequest;
import com.fams.product.contract.model.AssetTagReplacementResult;
import com.fams.product.contract.model.AssetTagReprintRequest;
import com.fams.product.contract.model.AssetUpdateRequest;
import com.fams.product.contract.model.PageAsset;
import com.fams.product.contract.model.PageAssetTimelineEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1")
class AssetController {
    private final AssetService service;
    private final AssetActorContext actors;

    AssetController(AssetService service, AssetActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/assets")
    ResponseEntity<PageAsset> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize,
            @RequestParam Map<String, String> filters) {
        return ResponseEntity.ok(service.list(actors.requireRead(), page, pageSize, filters));
    }

    @PostMapping("/assets")
    ResponseEntity<Asset> create(@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody AssetCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(actors.requireWarehouseOperator(), body, key));
    }

    @GetMapping("/assets/{assetId}")
    ResponseEntity<Asset> get(@PathVariable String assetId) {
        return ResponseEntity.ok(service.get(actors.requireRead(), parseId(assetId)));
    }

    @PatchMapping("/assets/{assetId}")
    ResponseEntity<Asset> update(@PathVariable String assetId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody AssetUpdateRequest body) {
        return ResponseEntity.ok(service.update(actors.requireWarehouseOperator(),
                parseId(assetId), version, body, key));
    }

    @PostMapping("/assets/{assetId}/copy")
    ResponseEntity<AssetCopyResult> copy(@PathVariable String assetId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody AssetCopyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.copy(
                actors.requireWarehouseOperator(), parseId(assetId), version,
                body.getQuantity(), body.getReason(), key));
    }

    @PostMapping("/assets/{assetId}/cancel")
    ResponseEntity<Asset> cancel(@PathVariable String assetId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody AssetCancelRequest body) {
        return ResponseEntity.ok(service.cancel(actors.requireWarehouseOperator(), parseId(assetId),
                version, body.getReason(), key));
    }

    @GetMapping("/assets/{assetId}/timeline")
    ResponseEntity<PageAssetTimelineEvent> timeline(@PathVariable String assetId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        return ResponseEntity.ok(service.timeline(actors.requireRead(), parseId(assetId), page, pageSize));
    }

    @GetMapping("/assets/{assetId}/tag")
    ResponseEntity<AssetTag> tag(@PathVariable String assetId) {
        return ResponseEntity.ok(service.tag(actors.requireRead(), parseId(assetId)));
    }

    @PostMapping("/assets/{assetId}/tag/replace")
    ResponseEntity<AssetTagReplacementResult> replaceTag(@PathVariable String assetId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody AssetTagReplaceRequest body) {
        return ResponseEntity.ok(service.replaceTag(actors.requireWarehouseOperator(),
                parseId(assetId), version, body.getReason(), key));
    }

    @PostMapping("/assets/{assetId}/tag/reprint")
    ResponseEntity<AssetTagPrintJob> reprint(@PathVariable String assetId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestBody(required = false) AssetTagReprintRequest body) {
        AssetTagPrintJob job = service.reprint(actors.requireWarehouseOperator(), parseId(assetId),
                version, body == null ? null : body.getReason(), key);
        return ResponseEntity.accepted()
                .header("Location", "/api/v1/asset-tag-print-jobs/" + job.getJobId()).body(job);
    }

    @PostMapping("/asset-tag-print-jobs")
    ResponseEntity<AssetTagBatchPrintJob> batchPrint(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody AssetTagBatchPrintRequest body) {
        AssetTagBatchPrintJob job = service.batchPrint(actors.requireWarehouseOperator(), body, key);
        return ResponseEntity.accepted()
                .header("Location", "/api/v1/asset-tag-print-jobs/" + job.getJobId()).body(job);
    }

    @GetMapping("/asset-tag-print-jobs/{printJobId}")
    ResponseEntity<AssetTagPrintJobResult> printJob(@PathVariable String printJobId) {
        return ResponseEntity.ok(service.printJob(actors.requireWarehouseOperator(), parseId(printJobId)));
    }

    @GetMapping("/asset-access/{token}")
    ResponseEntity<AssetQrAccessResult> resolve(@PathVariable String token) {
        return ResponseEntity.ok(service.resolve(actors.requireRead(), token));
    }

    private long parseId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result > 0) { return result; }
        } catch (NumberFormatException ignored) { }
        throw AssetOperationException.notFound();
    }
}
