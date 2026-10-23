package com.fams.requisition;

import com.fams.attachment.AttachmentBusinessPort;
import com.fams.auth.Role;
import com.fams.proxy.ProxyReceiptPort;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Evidence is append-only while draft; submitted and approved bytes cannot be corrected in place.
 */
@Component
@Profile("fams-product")
public class ProxyConfirmationAttachmentPort implements AttachmentBusinessPort {

    private final JdbcTemplate db;

    private final Map<String, ProxyReceiptPort> businesses;

    public ProxyConfirmationAttachmentPort(JdbcTemplate db, List<ProxyReceiptPort> businesses) {
        this.db = db;
        this.businesses = businesses.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ProxyReceiptPort::businessType, value -> value));
    }

    private ProxyReceiptPort business(Map<String, Object> row) {
        var port = businesses.get(row.get("business_type").toString());
        if (port == null) { throw RequisitionFailure.unavailable(); }
        return port;
    }

    @Override
    public String businessType() {
        return "PROXY_CONFIRMATION";
    }

    private Map<String, Object> row(long id, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_proxy_confirmation WHERE id=? AND"
                            + " business_type IN ('REQUISITION','TRANSFER','REPAIR')",
                        id);
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        long request = ((Number) rows.getFirst().get("business_id")).longValue();
        if (lock) { business(rows.getFirst()).lockHeader(request); }
        return db.queryForMap(
                "SELECT * FROM fams_proxy_confirmation WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                id);
    }

    private boolean processor(long actor, Map<String, Object> row, boolean lock) {
        return business(row).currentProcessor(actor, ((Number) row.get("business_id")).longValue(), lock);
    }

    @Override
    public void requireReaderLocked(long actor, Set<Role> roles, long id) {
        var row = row(id, true);
        if (roles.contains(Role.ASSET_ADMIN) && !"DRAFT".equals(row.get("confirmation_status"))) {
            return;
        }
        if (roles.contains(Role.WAREHOUSE_OPERATOR)
                && (((Number) row.get("creator_account_id")).longValue() == actor
                        || processor(actor, row, true))) {
            return;
        }
        throw RequisitionFailure.unavailable();
    }

    @Override
    public void requireUploadAccess(long actor, long id) {
        requireWritable(actor, id, -1, false);
    }

    @Override
    public void requireWritable(long actor, long id, long version, boolean lock) {
        var row = row(id, lock);
        if (!"DRAFT".equals(row.get("confirmation_status"))
                || ((Number) row.get("creator_account_id")).longValue() != actor
                || !processor(actor, row, lock)) {
            throw RequisitionFailure.conflict("PROXY_RECEIPT_EVIDENCE_FROZEN");
        }
        if (version >= 0 && ((Number) row.get("version")).longValue() != version) {
            throw RequisitionFailure.conflict("PROXY_RECEIPT_VERSION_CONFLICT");
        }
    }

    @Override
    public void registered(long actor, long id, long version) {
        int updated =
                db.update(
                        "UPDATE fams_proxy_confirmation SET version=version+1,updated_at=NOW(3)"
                            + " WHERE id=? AND version=? AND confirmation_status='DRAFT' AND"
                            + " creator_account_id=?",
                        id,
                        version,
                        actor);
        if (updated != 1) {
            throw RequisitionFailure.conflict("PROXY_RECEIPT_VERSION_CONFLICT");
        }
    }
}
