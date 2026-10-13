package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.targetchange.TargetChangeFailure;
import com.fams.targetchange.TargetChangePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Profile("fams-product")
class TransferTargetChangePort implements TargetChangePort {
    private final TransferService transfers;
    private final TransferProxyReceiptPort ownership;
    private final ObjectMapper json;
    TransferTargetChangePort(TransferService transfers, TransferProxyReceiptPort ownership, ObjectMapper json) {
        this.transfers = transfers;
        this.ownership = ownership;
        this.json = json;
    }
    @Override public String businessType() { return "TRANSFER"; }
    @Override public void lockHeader(long business) { translate(() -> { ownership.lockHeader(business);
            return null;
        }); }
    @Override public void requireProcessor(long account, long business) { translate(() -> { ownership.requireProcessor(account, business);
            return null;
        }); }
    @Override public Map<String, Object> context(Actor actor, long business, long item) { return translate(() -> transfers.targetChangeContext(actor, business, item)); }
    @Override public JsonNode canonicalTarget(JsonNode input) {
        return json.valueToTree(TransferService.targetChangeTarget(decode(input)));
    }
    @Override public void validateTarget(long business, long item, JsonNode target) {
        translate(() -> { transfers.validateChangedTarget(business, item, decode(target));
            return null;
        });
    }
    @Override public void apply(Actor actor, long business, long item, JsonNode target, String observedCode, long proposal) {
        translate(() -> { transfers.applyChangedTarget(actor, business, item, decode(target), observedCode, proposal);
            return null;
        });
    }
    private AssetTransferPort.Target decode(JsonNode input) {
        try {
            var target = json.treeToValue(input, AssetTransferPort.Target.class);
            if (target == null || target.locationId() < 1 || target.custodyType() == null || target.referenceVersions() == null
                    || target.referenceVersions().values().stream().anyMatch(value -> value == null || value < 0)) {
                throw TargetChangeFailure.invalid("TRANSFER_TARGET_INVALID");
            }
            return target;
        } catch (JacksonException | IllegalArgumentException error) { throw TargetChangeFailure.invalid("TRANSFER_TARGET_INVALID"); }
    }
    private <T> T translate(Supplier<T> action) {
        try { return action.get(); }
        catch (TransferFailure error) { throw new TargetChangeFailure(error.getMessage(), error.status()); }
        catch (AssetHandoverPort.Failure error) { throw new TargetChangeFailure(error.getMessage(), error.status()); }
    }
}
