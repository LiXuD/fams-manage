package com.fams.assetimport;

import com.fams.auth.AssetActorContext;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@RequestMapping("/api/v1/asset-import-batches")
class AssetImportReviewController {
    record DecisionRequest(boolean approved, String reason, Integer confirmedReadyRows,
                           Integer confirmedErrorRows, Boolean sampleVerified) { }

    private final AssetImportReviewService reviews;
    private final AssetActorContext actors;

    AssetImportReviewController(AssetImportReviewService reviews, AssetActorContext actors) {
        this.reviews = reviews;
        this.actors = actors;
    }

    @GetMapping("/reviews/pending")
    ResponseEntity<List<AssetImportReviewService.Pending>> pending(
            @RequestParam(defaultValue = "1") int page) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok(reviews.pending(page));
    }

    @GetMapping("/{batchId}/review")
    ResponseEntity<AssetImportReviewService.Review> get(@PathVariable UUID batchId) {
        return ResponseEntity.ok(reviews.getOwned(actors.requireWarehouseOperator().id(), batchId));
    }

    @GetMapping("/{batchId}/review-rows")
    ResponseEntity<AssetImportBatchService.RowPage> rows(@PathVariable UUID batchId,
            @RequestParam(defaultValue = "ERROR") String status,
            @RequestParam(defaultValue = "1") int page) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok(reviews.rowsForAdmin(batchId, status, page));
    }

    @PostMapping("/{batchId}/review")
    ResponseEntity<AssetImportReviewService.Review> request(@PathVariable UUID batchId,
            @RequestHeader("If-Match-Version") long version) {
        return ResponseEntity.ok(reviews.request(actors.requireWarehouseOperator().id(), batchId, version));
    }

    @PostMapping("/{batchId}/decision")
    ResponseEntity<AssetImportReviewService.Review> decide(@PathVariable UUID batchId,
            @RequestBody DecisionRequest request) {
        return ResponseEntity.ok(reviews.decide(actors.requireAssetAdmin().id(), batchId,
                request.approved(), request.reason(), request.confirmedReadyRows(),
                request.confirmedErrorRows(), Boolean.TRUE.equals(request.sampleVerified())));
    }
}
