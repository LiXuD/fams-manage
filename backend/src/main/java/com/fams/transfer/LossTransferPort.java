package com.fams.transfer;

import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Creates the formal transfer and adopts the exact active loss lock. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossTransferPort {
    public record Source(long reportId, long lossItemId, long lockVersion) {}

    private final TransferService transfers;

    LossTransferPort(TransferService transfers) {
        this.transfers = transfers;
    }

    public Map<String, Object> create(Actor actor, long asset, long version,
            AssetTransferPort.Target target, String reason, Source source) {
        try {
            return transfers.createFromLoss(actor, asset, version, target, reason, source);
        } catch (TransferFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
    }

    public static final class Failure extends RuntimeException {
        private final int status;

        Failure(String code, int status) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
