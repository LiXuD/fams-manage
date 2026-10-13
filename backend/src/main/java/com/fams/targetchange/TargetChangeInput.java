package com.fams.targetchange;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.Map;

public final class TargetChangeInput {
    private TargetChangeInput() {}
    public record Proposal(@Min(1) long businessId, @Min(1) long itemId,
            @NotNull Map<String, Object> snapshot, @NotNull JsonNode target,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 128) String observedAssetCode,
            @NotBlank @Size(max = 1000) String physicalVerification,
            @AssertTrue boolean physicalChecked) {
        public Proposal {
            snapshot = snapshot == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot));
        }
        Proposal withTarget(JsonNode value) {
            return new Proposal(businessId, itemId, snapshot, value, reason,
                    observedAssetCode, physicalVerification, physicalChecked);
        }
    }
    public record Decision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String result,
            @NotBlank @Size(max = 1000) String reason) {}
}
