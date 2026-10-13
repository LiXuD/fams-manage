package com.fams.proxy;

import com.fams.auth.RequisitionActorContext.Actor;

import java.util.Map;

/** Domain-owned receipt facts; evidence approval cannot bypass the original business transaction. */
public interface ProxyReceiptPort {
    String businessType();
    void lockHeader(long request);
    void requireProcessor(long account, long request);
    boolean currentProcessor(long account, long request, boolean lock);
    Map<String, Object> snapshot(Actor actor, long request, long item, boolean executing);
    Map<String, Object> execute(Actor actor, long request, long item,
            Map<String, Object> expected, String observedCode, long confirmation);
}
