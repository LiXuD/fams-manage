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
import java.util.UUID;

/**
 * Inventory executor/admin access is independent of other domains' warehouse-only reader policy.
 */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
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

    @Transactional
    public List<AttachmentEvidencePort.Metadata> list(
            RequisitionActorContext.Actor source, long task, UUID event) {
        return attachments.list("INVENTORY_EVENT", authorize(source, task, event));
    }

    /**
     * Frozen photo-list wire item: the pre-refactor controller copied every row key and only
     * rewrote createdAt (Timestamp/LocalDateTime become ISO instant strings, anything else
     * passes through). The named mapping keeps that projection field for field.
     */
    public record Photo(
            String objectId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            long businessVersion,
            Object createdAt) {
    }

    public static Photo photo(AttachmentEvidencePort.Metadata row) {
        Object created = row.createdAt();
        if (created instanceof java.sql.Timestamp time) {
            created = time.toInstant().toString();
        } else if (created instanceof java.time.LocalDateTime time) {
            created = time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return new Photo(
                row.objectId(),
                row.filename(),
                row.contentType(),
                row.sizeBytes(),
                row.scanStatus(),
                row.businessVersion(),
                created);
    }

    @Transactional
    public AttachmentGrantSigner.Grant grant(
            RequisitionActorContext.Actor source, long task, UUID event, UUID object) {
        long scope = authorize(source, task, event);
        return attachments.grant("INVENTORY_EVENT", scope, identity(source, object));
    }

    @Transactional
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
