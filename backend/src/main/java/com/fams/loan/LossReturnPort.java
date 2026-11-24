package com.fams.loan;

import com.fams.auth.RequisitionActorContext.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Return owns the formal application, approval, retained lock and physical stock acceptance. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossReturnPort {
    private static final Logger LOG = LoggerFactory.getLogger(LossReturnPort.class);

    public record Source(String reportId, String lossItemId, long lockVersion, String evidenceJson) {}

    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }
        public int status() { return status; }
    }

    private final AssetReturnService returns;
    public LossReturnPort(AssetReturnService returns) { this.returns = returns; }

    public LoanViews.AssetReturn create(Actor actor, UUID key, long asset, long version,
            String reason, Source source) {
        LOG.debug("Proposing loss-recovery asset return actor={} asset={} version={}",
                actor.id(), asset, version);
        try {
            return returns.proposeLossRecovery(actor, key.toString(), asset, version, reason, source);
        } catch (LoanFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
    }

    public void restoreMissing(long followupId, long asset, long report) {
        LOG.debug("Restoring missing asset={} via followup={} report={}", asset, followupId, report);
        try {
            returns.restoreMissing(followupId, asset, report);
        } catch (LoanFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
    }

}
