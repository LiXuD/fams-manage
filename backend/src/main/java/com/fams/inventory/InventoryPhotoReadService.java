package com.fams.inventory;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.auth.RequisitionActorContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory executor/admin access is independent of other domains' warehouse-only reader policy.
 */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@Transactional
public class InventoryPhotoReadService {
    private final RequisitionActorContext actors;
    private final InventoryAttachmentPort business;
    private final AttachmentEvidencePort attachments;
    private final JdbcTemplate db;

    public InventoryPhotoReadService(
            RequisitionActorContext actors,
            InventoryAttachmentPort business,
            AttachmentEvidencePort attachments,
            JdbcTemplate db) {
        this.actors = actors;
        this.business = business;
        this.attachments = attachments;
        this.db = db;
    }

    private long authorize(RequisitionActorContext.Actor source, long task, UUID event) {
        var current = actors.revalidateLocked(source);
        var rows =
                db.queryForList(
                        "SELECT id FROM fams_inventory_upload_scope WHERE task_id=? AND"
                            + " client_event_id=?",
                        task,
                        event.toString());
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_PHOTO_NOT_FOUND");
        }
        long scope = ((Number) rows.getFirst().get("id")).longValue();
        business.requireReaderLocked(current.id(), current.roles(), scope);
        return scope;
    }

    public List<Map<String, Object>> list(
            RequisitionActorContext.Actor source, long task, UUID event) {
        return attachments.list("INVENTORY_EVENT", authorize(source, task, event));
    }

    public AttachmentGrantSigner.Grant grant(
            RequisitionActorContext.Actor source, long task, UUID event, UUID object) {
        long scope = authorize(source, task, event);
        return attachments.grant("INVENTORY_EVENT", scope, identity(source, object));
    }

    public AttachmentReadService.Content read(
            RequisitionActorContext.Actor source, long task, UUID event, UUID object, String token)
            throws IOException {
        long scope = authorize(source, task, event);
        return attachments.read("INVENTORY_EVENT", scope, identity(source, object), token);
    }

    private AttachmentGrantSigner.Identity identity(
            RequisitionActorContext.Actor source, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, source.id(), source.authEpoch(), source.authorizationVersion());
    }
}
