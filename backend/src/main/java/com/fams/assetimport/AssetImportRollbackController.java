package com.fams.assetimport;

import com.fams.auth.AssetActorContext;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@RequestMapping("/api/v1/asset-import-batches")
class AssetImportRollbackController {
    record ReasonRequest(String reason) { }
    record DecisionRequest(boolean approved, String reason) { }

    private final AssetImportRollbackService rollbacks;
    private final AssetActorContext actors;

    AssetImportRollbackController(AssetImportRollbackService rollbacks, AssetActorContext actors) {
        this.rollbacks = rollbacks;
        this.actors = actors;
    }

    @PostMapping("/{batchId}/rollback")
    ResponseEntity<AssetImportRollbackService.Rollback> request(@PathVariable UUID batchId,
            @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(rollbacks.request(actors.requireWarehouseOperator().id(),
                batchId, request.reason()));
    }

    @PostMapping("/{batchId}/rollback-decision")
    ResponseEntity<AssetImportRollbackService.Rollback> decide(@PathVariable UUID batchId,
            @RequestBody DecisionRequest request) {
        return ResponseEntity.ok(rollbacks.decide(actors.requireAssetAdmin().id(), batchId,
                request.approved(), request.reason()));
    }

    @PostMapping("/{batchId}/rollback-execute")
    ResponseEntity<AssetImportRollbackService.Rollback> execute(@PathVariable UUID batchId) {
        return ResponseEntity.ok(rollbacks.queue(actors.requireWarehouseOperator().id(), batchId));
    }
}
