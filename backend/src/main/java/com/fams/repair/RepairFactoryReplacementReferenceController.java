package com.fams.repair;

import com.fams.auth.RequisitionActorContext;
import com.fams.masterdata.FactoryReplacementCatalogPort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping({"/api/v1/repairs/{id}/factory/rounds/{round}/replacement-references",
        "/api/v1/mini/repairs/{id}/factory/rounds/{round}/replacement-references"})
public class RepairFactoryReplacementReferenceController {
    private final RequisitionActorContext actors;
    private final RepairFactoryService factory;
    private final FactoryReplacementCatalogPort catalogs;

    public RepairFactoryReplacementReferenceController(RequisitionActorContext actors,
            RepairFactoryService factory, FactoryReplacementCatalogPort catalogs) {
        this.actors = actors;
        this.factory = factory;
        this.catalogs = catalogs;
    }

    @GetMapping("/{kind}")
    @Transactional
    Object references(@PathVariable @Positive long id, @PathVariable @Positive long round,
            @PathVariable @Pattern(regexp = "category|organization|warehouse|location") String kind,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        if (!Boolean.TRUE.equals(factory.replacements(actors.capture(), id, round).get("canRecord"))) {
            throw new RepairFailure(404, "REPAIR_FACTORY_REPLACEMENT_NOT_AVAILABLE");
        }
        return catalogs.references(kind, page, pageSize);
    }
}
