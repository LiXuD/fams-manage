package com.fams.inventory;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.repair.InventoryRepairPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/** Revalidates the formal repair owner and the original inventory event on every byte read. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class InventoryRepairPhotoReadService {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryRepairPhotoReadService.class);
    private final InventoryRepairPort repairs;
    private final AttachmentEvidencePort attachments;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    /**
     * Injects the collaborators needed to serve original inventory evidence through the repair owner.
     *
     * @param repairs proves the caller owns the formal repair and exposes the evidence lineage
     * @param attachments lists, grants and reads the quarantined INVENTORY_EVENT evidence bytes
     * @param db resolves the submitted, non-discarded upload scope of the original observation
     * @param json compares the stored observation facts with the repair-side original facts
     */
    public InventoryRepairPhotoReadService(
            InventoryRepairPort repairs,
            AttachmentEvidencePort attachments,
            JdbcTemplate db,
            ObjectMapper json) {
        this.repairs = repairs;
        this.attachments = attachments;
        this.db = db;
        this.json = json;
    }

    private long authorize(Actor actor, long repair, long observation, UUID object) {
        InventoryRepairPort.Evidence evidence;
        try {
            evidence = repairs.evidence(actor, repair, observation);
        } catch (InventoryRepairPort.Failure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        }
        if (!evidence.photoIds().contains(object)) {
            throw new InventoryFailure(404, "INVENTORY_REPAIR_PHOTO_NOT_FOUND");
        }
        var rows =
                db.queryForList(
                        "SELECT o.facts_json,s.id FROM fams_inventory_observation o JOIN"
                            + " fams_inventory_upload_scope s ON s.task_id=o.task_id AND"
                            + " s.client_event_id=o.client_event_id WHERE o.id=? AND o.task_id=?"
                            + " AND o.client_event_id=? AND s.discarded_at IS NULL AND"
                            + " s.submitted_at IS NOT NULL FOR UPDATE",
                        observation,
                        evidence.taskId(),
                        evidence.clientEventId());
        if (rows.size() != 1
                || !json.readTree(rows.getFirst().get("facts_json").toString())
                        .equals(json.readTree(evidence.originalFactsJson()))) {
            throw new InventoryFailure(409, "INVENTORY_REPAIR_SOURCE_CHANGED");
        }
        boolean present = false;
        for (var photo :
                json.readTree(rows.getFirst().get("facts_json").toString()).path("photoIds")) {
            present |= object.toString().equals(photo.asText());
        }
        if (!present) {
            throw new InventoryFailure(404, "INVENTORY_REPAIR_PHOTO_NOT_FOUND");
        }
        return ((Number) rows.getFirst().get("id")).longValue();
    }

    /**
     * Signs a download grant for one original inventory photo as seen through its formal repair. The
     * grant is bound to the caller's identity, auth epoch and authorization version, so losing the
     * repair ownership also invalidates a grant that was already handed out.
     *
     * @param actor caller whose repair ownership is revalidated on this call
     * @param repair formal repair id the caller owns
     * @param observation original inventory observation whose evidence is requested
     * @param object photo object id to grant a download for
     * @return signed grant with its expiry epoch second
     * @throws InventoryFailure the mapped status and code of a repair-port refusal, 404
     *     INVENTORY_REPAIR_PHOTO_NOT_FOUND when the object is neither in the repair evidence nor in
     *     the stored observation facts, or 409 INVENTORY_REPAIR_SOURCE_CHANGED when no single
     *     submitted scope row matches or the stored facts differ from the repair's original facts
     */
    @Transactional
    public AttachmentGrantSigner.Grant grant(
            Actor actor, long repair, long observation, UUID object) {
        LOG.debug("Repair photo grant requested for observation {} by actor {}.", observation, actor.id());
        return attachments.grant(
                "INVENTORY_EVENT",
                authorize(actor, repair, observation, object),
                identity(actor, object));
    }

    /**
     * Lists the original inventory photos that back a formal repair. A repair whose evidence carries
     * no photo id returns an empty list without touching the upload scope; otherwise the first photo
     * id authorizes the scope and the result is filtered to the evidence's own ids, so unrelated
     * photos of the same client event are never exposed.
     *
     * @param actor caller whose repair ownership is revalidated on this call
     * @param repair formal repair id the caller owns
     * @param observation original inventory observation whose evidence is requested
     * @return attachment metadata rows of the evidence photo ids, possibly empty
     * @throws InventoryFailure the mapped status and code of a repair-port refusal, 404
     *     INVENTORY_REPAIR_PHOTO_NOT_FOUND when the authorizing object is missing from the evidence or
     *     the stored facts, or 409 INVENTORY_REPAIR_SOURCE_CHANGED when no single submitted scope row
     *     matches or the stored facts differ from the repair's original facts
     */
    @Transactional
    public java.util.List<AttachmentEvidencePort.Metadata> list(
            Actor actor, long repair, long observation) {
        LOG.debug("Repair photo list requested for repair {} observation {}.", repair, observation);
        InventoryRepairPort.Evidence evidence;
        try {
            evidence = repairs.evidence(actor, repair, observation);
        } catch (InventoryRepairPort.Failure failure) {
            throw new InventoryFailure(failure.status(), failure.getMessage());
        }
        if (evidence.photoIds().isEmpty()) {
            return java.util.List.of();
        }
        long scope = authorize(actor, repair, observation, evidence.photoIds().getFirst());
        return attachments.list("INVENTORY_EVENT", scope).stream()
                .filter(
                        row ->
                                evidence.photoIds().stream()
                                        .anyMatch(id -> id.toString().equals(row.objectId())))
                .toList();
    }

    /**
     * Reads one original inventory photo's bytes through its formal repair, revalidating the repair
     * owner and the evidence lineage on every byte read. Grant verification and the content hash
     * check stay with the attachment port.
     *
     * @param actor caller whose repair ownership is revalidated on this call
     * @param repair formal repair id the caller owns
     * @param observation original inventory observation whose evidence is requested
     * @param object photo object id to read
     * @param token signed grant token previously returned by {@link #grant}
     * @return original filename, content type and bytes of the photo
     * @throws IOException when the quarantined object cannot be opened or read from storage
     * @throws InventoryFailure the mapped status and code of a repair-port refusal, 404
     *     INVENTORY_REPAIR_PHOTO_NOT_FOUND when the object is neither in the repair evidence nor in
     *     the stored observation facts, or 409 INVENTORY_REPAIR_SOURCE_CHANGED when no single
     *     submitted scope row matches or the stored facts differ from the repair's original facts
     */
    @Transactional
    public AttachmentReadService.Content read(
            Actor actor, long repair, long observation, UUID object, String token)
            throws IOException {
        LOG.debug("Repair photo read requested for repair {} observation {}.", repair, observation);
        return attachments.read(
                "INVENTORY_EVENT",
                authorize(actor, repair, observation, object),
                identity(actor, object),
                token);
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
