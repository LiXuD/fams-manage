package com.fams.asset;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import com.fams.auth.AssetActorContext;
import com.fams.product.contract.model.AssetSavedFilter;
import com.fams.product.contract.model.AssetSavedFilterCreateRequest;
import com.fams.product.contract.model.AssetSavedFilterUpdateRequest;
import com.fams.product.contract.model.PageAssetSavedFilter;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/asset-saved-filters")
class AssetPreferenceController {
    private final AssetPreferenceService service;
    private final AssetActorContext actors;
    private final HttpServletRequest request;

    AssetPreferenceController(AssetPreferenceService service, AssetActorContext actors,
            HttpServletRequest request) {
        this.service = service;
        this.actors = actors;
        this.request = request;
    }

    @GetMapping
    ResponseEntity<PageAssetSavedFilter> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        return ResponseEntity.ok(service.list(actors.requireRead(), page, pageSize));
    }

    @PostMapping
    ResponseEntity<AssetSavedFilter> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody AssetSavedFilterCreateRequest body) {
        return ResponseEntity.status(201).body(service.create(actors.requireRead(), body, key,
                requestId()));
    }

    @PatchMapping("/{savedFilterId}")
    ResponseEntity<AssetSavedFilter> update(@PathVariable String savedFilterId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @Valid @RequestBody AssetSavedFilterUpdateRequest body) {
        return ResponseEntity.ok(service.update(actors.requireRead(), parseId(savedFilterId),
                version, body, key, requestId()));
    }

    @DeleteMapping("/{savedFilterId}")
    ResponseEntity<Void> delete(@PathVariable String savedFilterId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long version) {
        service.delete(actors.requireRead(), parseId(savedFilterId), version, key, requestId());
        return ResponseEntity.noContent().build();
    }

    private UUID requestId() {
        Object value = request.getAttribute("fams.request.id");
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }

    private long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) { return id; }
        } catch (NumberFormatException ignored) { }
        throw AssetOperationException.notFound();
    }
}
