package com.fams.transfer;

import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.proxy.ProxyReceiptPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
@Profile("fams-product")
class TransferProxyReceiptPort implements ProxyReceiptPort {
    private static final TypeReference<Map<String, Object>> WIRE_MAP = new TypeReference<>() {};

    private final JdbcTemplate db;
    private final TransferService transfers;
    private final ObjectMapper json;

    TransferProxyReceiptPort(JdbcTemplate db, TransferService transfers, ObjectMapper json) {
        this.db = db;
        this.transfers = transfers;
        this.json = json;
    }

    @Override public String businessType() { return "TRANSFER"; }

    @Override public void lockHeader(long request) {
        if (db.queryForList("SELECT id FROM fams_transfer WHERE id=? FOR UPDATE", request).isEmpty()) {
            throw new TransferFailure("TRANSFER_NOT_FOUND", 404);
        }
    }

    @Override public void requireProcessor(long account, long request) {
        lockHeader(request);
        if (!currentProcessor(account, request, true)) {
            throw new TransferFailure("TRANSFER_NOT_PROCESSOR", 409);
        }
    }

    @Override public boolean currentProcessor(long account, long request, boolean lock) {
        return !db.queryForList("SELECT id FROM fams_transfer WHERE id=? AND processor_account_id=?"
                + (lock ? " FOR UPDATE" : ""), request, account).isEmpty();
    }

    // The proxy envelope stays Map-typed until all three business adapters are typed together.
    @Override public Map<String, Object> snapshot(Actor actor, long request, long item, boolean executing) {
        return json.convertValue(transfers.proxySnapshot(actor, request, item, executing), WIRE_MAP);
    }

    @Override public Map<String, Object> execute(Actor actor, long request, long item,
            Map<String, Object> expected, String observedCode, long confirmation) {
        return json.convertValue(
                transfers.proxyReceipt(actor, request, item, expected, observedCode, confirmation), WIRE_MAP);
    }
}
