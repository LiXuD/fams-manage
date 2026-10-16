package com.fams.inventory;

import com.fams.attachment.AttachmentBusinessPort;
import com.fams.auth.Role;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Inventory owns attachment visibility; generic warehouse upload routes cannot write this business.
 */
@Component
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class InventoryAttachmentPort implements AttachmentBusinessPort {
    private final JdbcTemplate db;

    public InventoryAttachmentPort(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public String businessType() {
        return "INVENTORY_EVENT";
    }

    @Override
    public void requireReaderLocked(long actorId, Set<Role> roles, long scopeId) {
        var scopes =
                db.queryForList(
                        "SELECT task_id,actor_account_id FROM fams_inventory_upload_scope WHERE"
                                + " id=?",
                        scopeId);
        if (scopes.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_PHOTO_NOT_FOUND");
        }
        var scope = scopes.getFirst();
        long task = ((Number) scope.get("task_id")).longValue();
        if (db.queryForList(
                        "SELECT o.id FROM fams_inventory_observation o JOIN"
                            + " fams_inventory_upload_scope s ON s.task_id=o.task_id AND"
                            + " s.client_event_id=o.client_event_id WHERE s.id=? AND"
                            + " o.outcome<>'EXPIRED'",
                        scopeId)
                .isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_PHOTO_NOT_FOUND");
        }
        var participants =
                db.queryForList(
                        "SELECT revoked_at FROM fams_inventory_participant WHERE task_id=? AND"
                                + " account_id=?",
                        task,
                        actorId);
        if (!participants.isEmpty() && participants.getFirst().get("revoked_at") != null) {
            throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REVOKED");
        }
        if (!roles.contains(Role.ASSET_ADMIN)
                && (participants.isEmpty()
                        || ((Number) scope.get("actor_account_id")).longValue() != actorId)) {
            throw new InventoryFailure(403, "INVENTORY_PHOTO_NOT_AUTHORIZED");
        }
        db.queryForList("SELECT id FROM fams_inventory_task WHERE id=? FOR SHARE", task);
    }

    @Override
    public void requireUploadAccess(long actorId, long businessId) {
        throw new InventoryFailure(403, "INVENTORY_USE_EVENT_PHOTO_ROUTE");
    }

    @Override
    public void requireWritable(long actorId, long businessId, long version, boolean lock) {
        throw new InventoryFailure(403, "INVENTORY_USE_EVENT_PHOTO_ROUTE");
    }

    @Override
    public void registered(long actorId, long businessId, long version) {
        throw new InventoryFailure(403, "INVENTORY_USE_EVENT_PHOTO_ROUTE");
    }
}
