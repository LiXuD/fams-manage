package com.fams.repair;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetRepairPort;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.targetchange.TargetChangeFailure;
import com.fams.targetchange.TargetChangePort;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The shared workflow owns the decision; repair owns frozen scope and physical execution. */
@Component
@Profile("fams-product")
class RepairTargetChangePort implements TargetChangePort {
    private final RepairService repairs;
    private final ObjectMapper json;
    private final AssetRepairPort assets;

    RepairTargetChangePort(RepairService repairs, ObjectMapper json, AssetRepairPort assets) {
        this.repairs = repairs;
        this.json = json;
        this.assets = assets;
    }

    @Override
    public String businessType() {
        return "REPAIR";
    }

    @Override
    public void lockHeader(long business) {
        translated(
                () -> {
                    repairs.lockTargetChange(business);
                    return null;
                });
    }

    @Override
    public void requireProcessor(long account, long business) {
        translated(
                () -> {
                    repairs.requireTargetChangeProcessor(account, business);
                    return null;
                });
    }

    @Override
    public Map<String, Object> context(Actor actor, long business, long item) {
        return translated(() -> repairs.targetChangeContext(actor, business, item));
    }

    @Override
    public JsonNode canonicalTarget(JsonNode input) {
        if (input == null
                || !input.isObject()
                || !List.of("PERSONAL", "SHARED").contains(input.path("custodyType").asText())) {
            throw TargetChangeFailure.invalid("REPAIR_RETURN_TARGET_INVALID");
        }
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", input.path("custodyType").asText());
        for (String field : List.of("organizationId", "employeeId", "locationId")) {
            String value = input.path(field).asText();
            try {
                long id = Long.parseLong(value);
                if (id < 1) {
                    throw new NumberFormatException();
                }
                target.put(field, Long.toString(id));
            } catch (NumberFormatException error) {
                throw TargetChangeFailure.invalid("REPAIR_RETURN_TARGET_INVALID");
            }
        }
        for (String field : List.of("organizationVersion", "employeeVersion", "locationVersion")) {
            var value = input.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                throw TargetChangeFailure.invalid("REPAIR_RETURN_TARGET_INVALID");
            }
            target.put(field, value.asLong());
        }
        return json.valueToTree(target);
    }

    @Override
    public Map<String, String> targetDisplay(JsonNode target) {
        return assets.describeReturnTarget(target);
    }

    @Override
    public void validateTarget(long business, long item, JsonNode target) {
        translated(
                () -> {
                    repairs.validateChangedReturnTarget(business, item, target);
                    return null;
                });
    }

    @Override
    public void apply(
            Actor actor,
            long business,
            long item,
            JsonNode target,
            String observedCode,
            long proposal) {
        translated(
                () -> {
                    repairs.applyChangedReturnTarget(
                            actor, business, item, target, observedCode, proposal);
                    return null;
                });
    }

    private <T> T translated(Supplier<T> action) {
        try {
            return action.get();
        } catch (RepairFailure error) {
            throw new TargetChangeFailure(error.getMessage(), error.status());
        } catch (AssetHandoverPort.Failure error) {
            throw new TargetChangeFailure(error.getMessage(), error.status());
        }
    }
}
