package com.fams.asset;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("fams-product")
class AssetLookupController {
    private final RequisitionActorContext actors;
    private final AssetLookupService service;

    AssetLookupController(RequisitionActorContext actors, AssetLookupService service) {
        this.actors = actors;
        this.service = service;
    }

    @GetMapping({"/api/v1/asset-lookup", "/api/v1/mini/asset-lookup"})
    ResponseEntity<AssetLookupService.Result> lookup(@RequestParam @NotBlank @Size(max = 128) String assetCode,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.lookup(actors.capture(), assetCode.strip(), page));
    }
}
