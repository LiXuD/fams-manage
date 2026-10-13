package com.fams.targetchange;

import com.fams.auth.RequisitionActorContext.Actor;
import tools.jackson.databind.JsonNode;
import java.util.Map;

/** Business domains own physical facts, target eligibility and mutations; the workflow owns approvals. */
public interface TargetChangePort {
    String businessType();
    void lockHeader(long business);
    void requireProcessor(long account, long business);
    Map<String, Object> context(Actor actor, long business, long item);
    JsonNode canonicalTarget(JsonNode input);
    void validateTarget(long business, long item, JsonNode target);
    void apply(Actor actor, long business, long item, JsonNode target, String observedCode, long proposal);
}
