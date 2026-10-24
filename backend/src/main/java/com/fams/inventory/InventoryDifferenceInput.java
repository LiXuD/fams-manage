package com.fams.inventory;

import com.fams.asset.AssetTransferPort;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class InventoryDifferenceInput {
    private InventoryDifferenceInput() {}

    public record Prepare(
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Positive Long> observationIds) {
        public Prepare {
            observationIds = observationIds == null ? null : List.copyOf(observationIds);
        }
    }

    public record Subject(
            @Positive long observationId,
            @NotBlank String dimension,
            @PositiveOrZero long version) {}

    public record Decision(
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Subject> subjects,
            @PositiveOrZero long assetVersion,
            @NotBlank String method,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence,
            @Valid AssetTransferPort.Target transferTarget,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid Damage repair) {
        public Decision(
                List<Subject> subjects,
                long assetVersion,
                String method,
                String reason,
                String evidence,
                AssetTransferPort.Target transferTarget) {
            this(subjects, assetVersion, method, reason, evidence, transferTarget, null);
        }

        public Decision {
            subjects = subjects == null ? null : List.copyOf(subjects);
        }
    }

    public record Damage(
            @Positive Long repairId,
            @PositiveOrZero Long repairVersion,
            Instant verifiedDiscoveryAt,
            @Size(min = 3, max = 2000) String fault,
            String impact,
            boolean physicalDamageChecked,
            boolean discoveryTimeVerified) {}
}
