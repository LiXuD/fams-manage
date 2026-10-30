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

    public LossRepairPort(RepairService repairs) {
        this.repairs = repairs;
    }

    public Map<String, Object> create(
            Actor actor, UUID key, RepairInput.Submission input, Source source) {
        try {
            return repairs.submitLossRecovery(actor, key, input, source);
        } catch (RepairFailure failure) {
            throw new Failure(failure.status(), failure.getMessage());
        }
    }
}
