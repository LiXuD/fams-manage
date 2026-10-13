package com.fams.requisition;

import com.fams.attachment.AttachmentBusinessPort;
import com.fams.auth.Role;

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

    public ProxyConfirmationAttachmentPort(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public String businessType() {
        return "PROXY_CONFIRMATION";
    }

    private Map<String, Object> row(long id, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_proxy_confirmation WHERE id=? AND"
                            + " business_type='REQUISITION'",
                        id);
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        long request = ((Number) rows.getFirst().get("business_id")).longValue();
        db.queryForList(
                "SELECT id FROM fams_requisition WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                request);
        return db.queryForMap(
                "SELECT * FROM fams_proxy_confirmation WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                id);
    }

    private boolean processor(long actor, long request, boolean lock) {
        return !db.queryForList(
                        "SELECT request_id FROM fams_requisition_ownership WHERE request_id=? AND"
                            + " processor_account_id=? AND archived_at IS NULL"
                                + (lock ? " FOR UPDATE" : ""),
                        request,
                        actor)
                .isEmpty();
    }

    @Override
    public void requireReaderLocked(long actor, Set<Role> roles, long id) {
        var row = row(id, true);
        if (roles.contains(Role.ASSET_ADMIN) && !"DRAFT".equals(row.get("confirmation_status"))) {
            return;
        }
        if (roles.contains(Role.WAREHOUSE_OPERATOR)
                && (((Number) row.get("creator_account_id")).longValue() == actor
                        || processor(actor, ((Number) row.get("business_id")).longValue(), true))) {
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
                || !processor(actor, ((Number) row.get("business_id")).longValue(), lock)) {
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
