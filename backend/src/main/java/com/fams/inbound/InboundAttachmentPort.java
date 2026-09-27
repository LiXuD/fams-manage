package com.fams.inbound;

import com.fams.attachment.AttachmentBusinessPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Draft creators and the exclusive active executor may append inbound evidence. */
@Component
@Profile("fams-product")
public final class InboundAttachmentPort implements AttachmentBusinessPort {
    private final JdbcTemplate db;
    public InboundAttachmentPort(JdbcTemplate db) { this.db = db; }
    @Override public String businessType() { return "INBOUND"; }

    @Override public void requireUploadAccess(long actorId, long businessId) {
        requireWritable(actorId, businessId, -1, false);
    }

    @Override
    public void requireWritable(long actorId, long businessId, long expectedVersion, boolean lock) {
        var receipts = db.queryForList("SELECT creator_account_id,receipt_status,version FROM fams_inbound_receipt "
                + "WHERE id=?" + (lock ? " FOR UPDATE" : ""), businessId);
        if (receipts.isEmpty()) { throw InboundFailure.missing(); }
        var receipt = receipts.getFirst();
        String state = (String) receipt.get("receipt_status");
        boolean writable = "DRAFT".equals(state)
                && ((Number) receipt.get("creator_account_id")).longValue() == actorId;
        if ("WAREHOUSE_PROCESSING".equals(state)) {
            var owners = db.queryForList("SELECT processor_account_id FROM fams_inbound_processing_ownership "
                    + "WHERE receipt_id=? AND archived_at IS NULL" + (lock ? " FOR UPDATE" : ""), businessId);
            writable = owners.size() == 1
                    && ((Number) owners.getFirst().get("processor_account_id")).longValue() == actorId;
        }
        if (!writable) { throw InboundFailure.conflict("INBOUND_ATTACHMENT_READ_ONLY"); }
        if (expectedVersion >= 0 && ((Number) receipt.get("version")).longValue() != expectedVersion) {
            throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT");
        }
    }

    @Override
    public void registered(long actorId, long businessId, long expectedVersion) {
        int updated = db.update("UPDATE fams_inbound_receipt SET version=version+1,updated_by=?,updated_at=NOW(3) "
                + "WHERE id=? AND version=?", actorId, businessId, expectedVersion);
        if (updated != 1) { throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT"); }
    }
}
