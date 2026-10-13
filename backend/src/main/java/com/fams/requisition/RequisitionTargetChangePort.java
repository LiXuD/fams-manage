package com.fams.requisition;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.targetchange.TargetChangeFailure;
import com.fams.targetchange.TargetChangePort;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Profile("fams-product")
class RequisitionTargetChangePort implements TargetChangePort {
    private final RequisitionHandoverService handovers;
    private final RequisitionProxyReceiptPort ownership;
    private final ObjectMapper json;
    private final Validator validator;
    RequisitionTargetChangePort(RequisitionHandoverService handovers,
            RequisitionProxyReceiptPort ownership, ObjectMapper json, Validator validator) {
        this.handovers = handovers;
        this.ownership = ownership;
        this.json = json;
        this.validator = validator;
    }
    @Override public String businessType() { return "REQUISITION"; }
    @Override public void lockHeader(long business) { translate(() -> { ownership.lockHeader(business);
            return null;
        }); }
    @Override public void requireProcessor(long account, long business) {
        try { ownership.requireProcessor(account, business); }
        catch (RequisitionFailure error) { throw TargetChangeFailure.conflict("REQUISITION_OWNED_BY_ANOTHER"); }
    }
    @Override public Map<String, Object> context(Actor actor, long business, long item) {
        return translate(() -> handovers.targetChangeContext(actor, business, item));
    }
    @Override public JsonNode canonicalTarget(JsonNode input) { return json.valueToTree(decode(input)); }
    @Override public void validateTarget(long business, long item, JsonNode target) {
        translate(() -> { handovers.validateChangedTarget(business, item, decode(target));
            return null;
        });
    }
    @Override public void apply(Actor actor, long business, long item, JsonNode target, String observedCode, long proposal) {
        translate(() -> { handovers.applyChangedTarget(actor, business, item, decode(target), observedCode, proposal);
            return null;
        });
    }
    private ItemTargetChangeInput.Target decode(JsonNode input) {
        try {
            var target = json.treeToValue(input, ItemTargetChangeInput.Target.class);
            if (target == null || !validator.validate(target).isEmpty()) { throw TargetChangeFailure.invalid("TARGET_CHANGE_TARGET_INVALID"); }
            return target;
        } catch (JacksonException error) { throw TargetChangeFailure.invalid("TARGET_CHANGE_TARGET_INVALID"); }
    }
    private <T> T translate(Supplier<T> action) {
        try { return action.get(); }
        catch (RequisitionFailure error) { throw new TargetChangeFailure(error.code(), error.status()); }
        catch (AssetHandoverPort.Failure error) { throw new TargetChangeFailure(error.getMessage(), error.status()); }
    }
}
