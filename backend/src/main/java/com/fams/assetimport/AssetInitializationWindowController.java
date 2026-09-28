package com.fams.assetimport;

import com.fams.auth.AssetActorContext;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@RequestMapping("/api/v1/asset-initialization-windows")
class AssetInitializationWindowController {
    record OpenRequest(String assignedOperatorId, String reason, Instant expiresAt) { }

    private final AssetInitializationWindowService windows;
    private final AssetActorContext actors;

    AssetInitializationWindowController(AssetInitializationWindowService windows, AssetActorContext actors) {
        this.windows = windows;
        this.actors = actors;
    }

    @PostMapping
    ResponseEntity<AssetInitializationWindowService.Window> open(@RequestBody OpenRequest request) {
        var window = windows.open(actors.requireSystemAdmin().id(),
                Long.parseLong(request.assignedOperatorId()),
                request.reason(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(window);
    }

    @PostMapping("/{windowId}/close")
    ResponseEntity<AssetInitializationWindowService.Window> close(@PathVariable long windowId) {
        return ResponseEntity.ok(windows.close(actors.requireSystemAdmin().id(), windowId));
    }
}
