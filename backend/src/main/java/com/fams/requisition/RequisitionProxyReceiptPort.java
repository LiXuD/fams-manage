package com.fams.requisition;

import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.proxy.ProxyReceiptPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("fams-product")
class RequisitionProxyReceiptPort implements ProxyReceiptPort {
    private final JdbcTemplate db;
    private final RequisitionHandoverService handovers;

    RequisitionProxyReceiptPort(JdbcTemplate db, RequisitionHandoverService handovers) {
        this.db = db;
        this.handovers = handovers;
    }

    @Override public String businessType() { return "REQUISITION"; }

    @Override public void lockHeader(long request) {
        if (db.queryForList("SELECT id FROM fams_requisition WHERE id=? FOR UPDATE", request).isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
    }

    @Override public void requireProcessor(long account, long request) {
        lockHeader(request);
        var owners = db.queryForList("SELECT processor_account_id FROM fams_requisition_ownership"
                + " WHERE request_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE", request);
        if (owners.isEmpty() || ((Number) owners.getFirst().get("processor_account_id")).longValue() != account) {
            throw RequisitionFailure.conflict("PROXY_RECEIPT_NOT_PROCESSOR");
        }
    }

    @Override public boolean currentProcessor(long account, long request, boolean lock) {
        return !db.queryForList("SELECT request_id FROM fams_requisition_ownership WHERE request_id=?"
                + " AND processor_account_id=? AND archived_at IS NULL" + (lock ? " FOR UPDATE" : ""),
                request, account).isEmpty();
    }

    @Override public Map<String, Object> snapshot(Actor actor, long request, long item, boolean executing) {
        return handovers.proxySnapshot(actor, request, item, executing);
    }

    @Override public Map<String, Object> execute(Actor actor, long request, long item,
            Map<String, Object> expected, String observedCode, long confirmation) {
        return handovers.proxyReceipt(actor, request, item, expected, observedCode, confirmation);
    }
}
