package com.fams.repair;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RepairCollaborationInput {
    private RepairCollaborationInput() {}

    public record Invitation(
            @Positive long technicianAccountId,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record Opinion(
            @NotBlank @Size(min = 3, max = 2000) String judgement,
            @NotBlank @Size(min = 3, max = 2000) String method,
            @NotBlank String result,
            @NotNull Instant occurredAt,
            @NotNull @Size(max = 10) List<@NotNull UUID> attachmentIds) {
        public Opinion {
            if (attachmentIds != null) {
                attachmentIds =
                        java.util.Collections.unmodifiableList(
                                new java.util.ArrayList<>(attachmentIds));
            }
        }

        @Override
        public List<UUID> attachmentIds() {
            return attachmentIds == null ? null : new java.util.ArrayList<>(attachmentIds);
        }
    }
}
