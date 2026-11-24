package com.fams.inbound;

import com.fams.attachment.AttachmentBusinessPort;
import com.fams.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Draft creators and the exclusive active executor may append inbound evidence. */
@Component
@Profile("fams-product")
public final class InboundAttachmentPort implements AttachmentBusinessPort {
    private static final Logger LOG = LoggerFactory.getLogger(InboundAttachmentPort.class);
    private final JdbcTemplate db;
    /**
     * Wires the port to the inbound receipt tables.
     *
     * @param db JDBC template used for receipt, ownership and version checks
     */
    public InboundAttachmentPort(JdbcTemplate db) { this.db = db; }
    @Override public String businessType() { return "INBOUND"; }

    /**
     * Read-access gate for inbound evidence, evaluated with the receipt row locked: asset admins
     * may read once the receipt was submitted; warehouse operators may also read their own drafts
     * and any receipt in the claim/processing/completed states. All other callers are rejected as
     * if the receipt did not exist, so unreadable ids do not become an existence oracle.
     *
     * @param actorId account id of the requesting actor
     * @param roles effective roles of the requesting actor
     * @param businessId inbound receipt id owning the attachment
     * @throws InboundFailure with code {@code INBOUND_NOT_FOUND} (404) when the receipt does not
     *         exist or is not readable by this actor
     */
    public void requireReaderLocked(long actorId, java.util.Set<Role> roles, long businessId) {
        var rows = db.queryForList("SELECT creator_account_id,receipt_status,submitted_at FROM fams_inbound_receipt "
                + "WHERE id=? FOR UPDATE", businessId);
        if (rows.isEmpty()) { throw InboundFailure.missing(); }
        var receipt = rows.getFirst();
        boolean readable = roles.contains(Role.ASSET_ADMIN) && receipt.get("submitted_at") != null;
        if (roles.contains(Role.WAREHOUSE_OPERATOR)) {
            readable = readable || ((Number) receipt.get("creator_account_id")).longValue() == actorId
                    || java.util.Set.of("WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "COMPLETED")
                        .contains(receipt.get("receipt_status"));
        }
        if (!readable) { throw InboundFailure.missing(); }
    }

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
        LOG.debug("UPDATE fams_inbound_receipt version bumped for receipt {}.", businessId);
        if (updated != 1) { throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT"); }
    }
}
