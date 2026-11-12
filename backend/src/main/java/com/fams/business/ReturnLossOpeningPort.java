package com.fams.business;

import com.fams.auth.RequisitionActorContext.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Loss owns formal reports opened against a locked return continuation. */
public interface ReturnLossOpeningPort {
    record Facts(Instant lastSeenAt, String lastLocation, String circumstances) {}
    record Opening(long returnId, long followupId, long assetId, long assetVersion, long processorAccountId,
            Facts facts) {}
    record Summary(String reportId, String status, boolean recovered, String recoveryReturnId) {}

    final class Failure extends RuntimeException {
        private final int status;

        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }

        public int status() { return status; }
    }

    long open(Actor actor, Opening opening);

    Map<Long, Summary> latest(List<Long> followupIds);

    default Summary latest(long followupId) {
        return latest(List.of(followupId)).get(followupId);
    }
}
