package com.fams.requisition;

import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.targetchange.TargetChangeFailure;
import com.fams.targetchange.TargetChangeInput;
import com.fams.targetchange.TargetChangeWorkflow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Supplier;

/** Preserve the original requisition API and typed-payload idempotency contract. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class ItemTargetChangeService {
    private final TargetChangeWorkflow workflow;
    private final ObjectMapper json;
    ItemTargetChangeService(TargetChangeWorkflow workflow, ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }
    Map<String, Object> context(Actor actor, long business, long item) {
        return call(() -> workflow.context(actor, "REQUISITION", business, item));
    }
    Map<String, Object> list(Actor actor, int page, int size) {
        return call(() -> workflow.list(actor, "REQUISITION", page, size));
    }
    Map<String, Object> propose(Actor actor, long version, String key, ItemTargetChangeInput.Proposal input) {
        var command = new TargetChangeInput.Proposal(input.businessId(), input.itemId(), input.snapshot(),
                json.valueToTree(input.target()), input.reason(), input.observedAssetCode(), input.physicalVerification(), input.physicalChecked());
        return call(() -> workflow.propose(actor, "REQUISITION", version, key, command));
    }
    Map<String, Object> decide(Actor actor, long id, long version, String key, ItemTargetChangeInput.Decision input) {
        return call(() -> workflow.decide(actor, "REQUISITION", id, version, key,
                new TargetChangeInput.Decision(input.result(), input.reason())));
    }
    private <T> T call(Supplier<T> action) {
        try { return action.get(); }
        catch (TargetChangeFailure error) {
            if ("TARGET_CHANGE_FORBIDDEN".equals(error.getMessage())) { throw RequisitionFailure.forbidden(); }
            if ("TARGET_CHANGE_UNAVAILABLE".equals(error.getMessage())) { throw RequisitionFailure.unavailable(); }
            throw error;
        }
    }
}
