package com.fams.loan;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/asset-returns", "/api/v1/mini/asset-returns"})
class AssetReturnController {
    record Proposal(@NotNull @Min(1) Long assetId, @Min(0) long assetVersion,
            @NotBlank @Size(max = 1000) String reason) {}
    record Decision(@NotNull Boolean approved, @NotBlank @Size(max = 1000) String reason) {}
    record Inspection(@NotBlank String result, @Size(max = 256) String observedCode,
            @NotBlank @Size(max = 1000) String reason, Long warehouseId, Long warehouseVersion,
            Long locationId, Long locationVersion, @Min(0) long assetVersion, @Min(0) long lockVersion) {}

    private final AssetReturnService service;
    private final RequisitionActorContext actors;

    AssetReturnController(AssetReturnService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/eligible-assets")
    Map<String, Object> eligible(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable long id) { return service.get(actors.capture(), id); }

    @GetMapping("/catalog/{kind}")
    Map<String, Object> catalog(@PathVariable String kind,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int pageSize) {
        return service.catalog(actors.capture(), kind, page, pageSize);
    }

    @PostMapping
    Map<String, Object> propose(@Valid @RequestBody Proposal input,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.propose(actors.capture(), key, input);
    }

    @PostMapping("/{id}/decision")
    Map<String, Object> decide(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Decision input) {
        return service.decide(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/claim")
    Map<String, Object> claim(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.claim(actors.capture(), id, version, key);
    }

    @PostMapping("/{id}/inspect")
    Map<String, Object> inspect(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Inspection input) {
        return service.inspect(actors.capture(), id, version, key, input);
    }
}
