package com.fams.asset;

import com.fams.auth.RequisitionActorContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("fams-product")
class AssetLedgerExportController {
    private final RequisitionActorContext actors;
    private final AssetLedgerExportService ledger;

    AssetLedgerExportController(RequisitionActorContext actors, AssetLedgerExportService ledger) {
        this.actors = actors;
        this.ledger = ledger;
    }

    @GetMapping("/api/v1/assets/export.csv")
    ResponseEntity<String> export(@RequestParam Map<String, String> filters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=asset-ledger.csv")
                .body(ledger.export(actors.capture(), filters));
    }
}
