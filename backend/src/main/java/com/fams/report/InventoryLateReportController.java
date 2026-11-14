package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Validated
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class InventoryLateReportController {
    private final AssetActorContext actors;
    private final InventoryLateReportService reports;

    InventoryLateReportController(AssetActorContext actors, InventoryLateReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Void> invalidFilter() {
        return ResponseEntity.badRequest().build();
    }

    @GetMapping("/inventory-late")
    ResponseEntity<InventoryLateReportService.Page> list(
            @RequestParam(required = false) @Positive Long taskId,
            @RequestParam(required = false) InventoryLateReportService.Result result,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(new InventoryLateReportService.Filter(taskId, result), before, pageSize));
    }

    @GetMapping("/inventory-late.csv")
    ResponseEntity<String> export(
            @RequestParam(required = false) @Positive Long taskId,
            @RequestParam(required = false) InventoryLateReportService.Result result) {
        var actor = actors.requireAssetAdmin();
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-late.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(reports.export(actor, new InventoryLateReportService.Filter(taskId, result)));
        } catch (InventoryLateReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }
}
