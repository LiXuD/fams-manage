package com.fams.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class InventoryEvidenceInput {
    private InventoryEvidenceInput() {}

    public record Reference(
            @NotBlank @Size(max = 32) String dimension, @PositiveOrZero long version) {}

    public record Decision(
            @Positive long observationId,
            @PositiveOrZero long recheckGeneration,
            @Positive long reviewVersion,
            @PositiveOrZero long assetVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contextDigest,
            @NotBlank @Size(max = 32) String method,
            @NotNull @Size(max = 13) List<@NotNull @Valid Reference> subjects,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {
        public Decision {
            subjects = subjects == null ? null : List.copyOf(subjects);
        }
    }

    public record Acceptance(
            @PositiveOrZero long recheckGeneration,
            @Positive long reviewVersion,
            @PositiveOrZero long assetVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contextDigest,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}
}
