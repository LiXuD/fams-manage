package com.fams.report;

import com.fams.auth.AssetActorContext;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
class AssetMovementTrendController {
    private final AssetActorContext actors;
    private final AssetMovementReportService reports;

    AssetMovementTrendController(AssetActorContext actors, AssetMovementReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/api/v1/operations-reports/movement-trend")
    ResponseEntity<List<AssetMovementReportService.TrendMonth>> trend() {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(reports.trend());
    }
}
