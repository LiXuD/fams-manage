package com.fams.repair;

import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Repair owns the linked application, its approval and the adopted lock. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossRepairPort {
    public record Source(String reportId, long lockVersion, String evidenceJson) {}

    /**
     * Narrow identity of the created repair: the only reader (LossService) consumes the view's
     * stringified "id" for the stored recovery/execution snapshot, the audit payload and the
     * repairId response key (JAVA-10c; RepairService.view already stringifies id).
     */
    public record Created(String id) {
        public static Created fromView(Map<String, Object> view) {
            Object id = view.get("id");
            return new Created(id == null ? null : id.toString());
        }
    }

    public static final class Failure extends RuntimeException {
        private final int status;

        public Failure(int status, String code) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final RepairService repairs;

    public Created createAfterConfirmation(
            Actor actor, UUID key, RepairInput.Submission input, Source source, long recoveryId) {
        try {
            return Created.fromView(
                    repairs.submitClosedLossRecovery(actor, key, input, source, recoveryId));
        } catch (RepairFailure failure) {
            throw new Failure(failure.status(), failure.getMessage());
        }
    }

    public LossRepairPort(RepairService repairs) {
        this.repairs = repairs;
    }

    public Created create(
            Actor actor, UUID key, RepairInput.Submission input, Source source) {
        try {
            return Created.fromView(repairs.submitLossRecovery(actor, key, input, source));
        } catch (RepairFailure failure) {
            throw new Failure(failure.status(), failure.getMessage());
        }
    }
}
