package com.fams.repair;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
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
    private final RepairFactoryReplacementReferenceService service;

    public RepairFactoryReplacementReferenceController(RequisitionActorContext actors,
            RepairFactoryReplacementReferenceService service) {
        this.actors = actors;
        this.service = service;
    }

    @GetMapping("/{kind}")
    Object references(@PathVariable @Positive long id, @PathVariable @Positive long round,
            @PathVariable @Pattern(regexp = "category|organization|warehouse|location") String kind,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        return service.references(actors.capture(), id, round, kind, page, pageSize);
    }
}
