package com.fams.assetimport;

import com.fams.auth.AssetActorContext;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@RequestMapping("/api/v1/asset-import-batches")
class AssetImportReviewController {
    record DecisionRequest(boolean approved, String reason) { }

    private final AssetImportReviewService reviews;
    private final AssetActorContext actors;

    AssetImportReviewController(AssetImportReviewService reviews, AssetActorContext actors) {
        this.reviews = reviews;
        this.actors = actors;
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
                request.approved(), request.reason()));
    }
}
