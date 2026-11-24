package com.fams.inventory;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.auth.RequisitionActorContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(InventoryPhotoReadService.class);
    private final RequisitionActorContext actors;
    private final InventoryAttachmentPort business;
    private final AttachmentEvidencePort attachments;
    private final JdbcTemplate db;

    /**
     * Injects the collaborators this reader needs to authorize and serve inventory event photos.
     *
     * @param actors revalidates the caller session under the task lock before any scope lookup
     * @param business enforces the inventory participation and admin reader policy per upload scope
     * @param attachments lists, grants and reads the quarantined INVENTORY_EVENT evidence bytes
     * @param db resolves the fams_inventory_upload_scope id of a task and client event id
     */
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

    /**
     * Lists the photo metadata of one inventory upload scope after authorizing the caller against it.
     * Authorization is re-checked on every call, so a participation revoked after the observation was
     * submitted stops the list immediately instead of serving stale evidence.
     *
     * @param source caller to revalidate under lock; must be an ASSET_ADMIN or the scope's own
     *     participant
     * @param task inventory task owning the upload scope
     * @param event client event id that identifies the scope within the task
     * @return attachment metadata rows of the INVENTORY_EVENT scope, possibly empty
     * @throws InventoryFailure 404 INVENTORY_PHOTO_NOT_FOUND when no scope matches the task and event
     *     id, when the scope has no non-expired observation, 403 INVENTORY_PARTICIPATION_REVOKED for
     *     a revoked participant, or 403 INVENTORY_PHOTO_NOT_AUTHORIZED for a caller who is neither an
     *     admin nor the scope owner
     */
    @Transactional
    public List<AttachmentEvidencePort.Metadata> list(
            RequisitionActorContext.Actor source, long task, UUID event) {
        LOG.debug("Inventory event photo list requested for task {} event {} by actor {}.", task, event, source.id());
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

    /**
     * Projects one attachment metadata row into the frozen photo wire item, copying every key and
     * rewriting only {@code createdAt}: both driver shapes become ISO instant strings while any other
     * value passes through untouched, exactly as the pre-refactor controller did.
     *
     * @param row attachment metadata of the INVENTORY_EVENT scope to project
     * @return the wire item with a normalized {@code createdAt}
     */
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

    /**
     * Signs a short-lived download grant for one photo of an inventory upload scope. The grant is
     * bound to the caller's identity, auth epoch and authorization version, so revoking the session
     * invalidates a grant that was already handed out.
     *
     * @param source caller to revalidate under lock and to bind the grant identity to
     * @param task inventory task owning the upload scope
     * @param event client event id that identifies the scope within the task
     * @param object photo object id to grant a download for
     * @return signed grant with its expiry epoch second
     * @throws InventoryFailure 404 INVENTORY_PHOTO_NOT_FOUND when no scope matches the task and event
     *     id or the scope has no non-expired observation, 403 INVENTORY_PARTICIPATION_REVOKED for a
     *     revoked participant, or 403 INVENTORY_PHOTO_NOT_AUTHORIZED for a caller who is neither an
     *     admin nor the scope owner
     */
    @Transactional
    public AttachmentGrantSigner.Grant grant(
            RequisitionActorContext.Actor source, long task, UUID event, UUID object) {
        LOG.debug("Inventory event photo grant requested for task {} event {} by actor {}.", task, event, source.id());
        long scope = authorize(source, task, event);
        return attachments.grant("INVENTORY_EVENT", scope, identity(source, object));
    }

    /**
     * Reads one photo's bytes after re-authorizing the caller against the upload scope. Grant
     * verification, metadata resolution and the content hash check stay with the attachment port, so
     * a token issued for another identity or a tampered object never reaches this domain's caller.
     *
     * @param source caller to revalidate under lock and to match the grant identity against
     * @param task inventory task owning the upload scope
     * @param event client event id that identifies the scope within the task
     * @param object photo object id to read
     * @param token signed grant token previously returned by {@link #grant}
     * @return original filename, content type and bytes of the photo
     * @throws IOException when the quarantined object cannot be opened or read from storage
     * @throws InventoryFailure 404 INVENTORY_PHOTO_NOT_FOUND when no scope matches the task and event
     *     id or the scope has no non-expired observation, 403 INVENTORY_PARTICIPATION_REVOKED for a
     *     revoked participant, or 403 INVENTORY_PHOTO_NOT_AUTHORIZED for a caller who is neither an
     *     admin nor the scope owner
     */
    @Transactional
    public AttachmentReadService.Content read(
            RequisitionActorContext.Actor source, long task, UUID event, UUID object, String token)
            throws IOException {
        LOG.debug("Inventory event photo read requested for task {} event {} by actor {}.", task, event, source.id());
        long scope = authorize(source, task, event);
        return attachments.read("INVENTORY_EVENT", scope, identity(source, object), token);
    }

    private AttachmentGrantSigner.Identity identity(
            RequisitionActorContext.Actor source, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, source.id(), source.authEpoch(), source.authorizationVersion());
    }
}
