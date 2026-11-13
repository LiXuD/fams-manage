package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class AssetCustodyReportController {
    private final AssetActorContext actors;
    private final AssetCustodyReportService reports;

    AssetCustodyReportController(AssetActorContext actors, AssetCustodyReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/asset-distribution")
    ResponseEntity<AssetCustodyReportService.Distribution> distribution() {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(reports.distribution());
    }

    @GetMapping("/assets")
    ResponseEntity<AssetCustodyReportService.Page> list(@jakarta.validation.Valid @ModelAttribute Filters filters,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(filters.toFilter(), before, pageSize));
    }

    @GetMapping("/assets.csv")
    ResponseEntity<String> export(@jakarta.validation.Valid @ModelAttribute Filters filters) {
        var actor = actors.requireAssetAdmin();
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=asset-custody-report.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(reports.export(actor, filters.toFilter()));
        } catch (AssetCustodyReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }

    public record Filters(@PositiveOrZero Long organizationId, @PositiveOrZero Long categoryId, @Positive Long responsibleId, @Positive Long warehouseId,
                          @Positive Long locationId, @Pattern(regexp = "PERSONAL|SHARED|STORAGE") String custody,
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inventoryBefore,
                          @Pattern(regexp = "IN_STOCK|IN_USE|BORROWED|UNDER_REPAIR|FACTORY_REPAIR|PENDING_SCRAP") String lifecycle,
                          @Pattern(regexp = "NORMAL|SEARCHING|LOST") String exception) {
        AssetCustodyReportService.Filter toFilter() {
            return new AssetCustodyReportService.Filter(organizationId, responsibleId, warehouseId,
                    locationId, custody, inventoryBefore, categoryId, lifecycle, exception);
        }
    }
}
