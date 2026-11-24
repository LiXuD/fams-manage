package com.fams.requisition;

import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.targetchange.TargetChangeFailure;
import com.fams.targetchange.TargetChangeInput;
import com.fams.targetchange.TargetChangeViews;
import com.fams.targetchange.TargetChangeWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.function.Supplier;

/** Preserve the original requisition API and typed-payload idempotency contract. */
@Service
@Profile("fams-product")
class ItemTargetChangeService {
    private static final Logger LOG = LoggerFactory.getLogger(ItemTargetChangeService.class);

    private final TargetChangeWorkflow workflow;
    private final ObjectMapper json;
    ItemTargetChangeService(TargetChangeWorkflow workflow, ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TargetChangeViews.Context context(Actor actor, long business, long item) {
        LOG.debug("Reading requisition target-change context actor={} business={} item={}", actor.id(), business, item);
        return call(() -> workflow.context(actor, "REQUISITION", business, item));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TargetChangeViews.Page list(Actor actor, int page, int size) {
        LOG.debug("Listing requisition target changes actor={} page={} size={}", actor.id(), page, size);
        return call(() -> workflow.list(actor, "REQUISITION", page, size));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TargetChangeViews.Change propose(Actor actor, long version, String key,
            ItemTargetChangeInput.Proposal input) {
        LOG.info("Proposing requisition target change actor={} business={} item={} version={}",
                actor.id(), input.businessId(), input.itemId(), version);
        var command = new TargetChangeInput.Proposal(input.businessId(), input.itemId(), input.snapshot(),
                json.valueToTree(input.target()), input.reason(), input.observedAssetCode(),
                input.physicalVerification(), input.physicalChecked());
        return call(() -> workflow.propose(actor, "REQUISITION", version, key, command));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TargetChangeViews.Change decide(Actor actor, long id, long version, String key,
            ItemTargetChangeInput.Decision input) {
        LOG.info("Deciding requisition target change actor={} changeId={} version={} result={}",
                actor.id(), id, version, input.result());
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
