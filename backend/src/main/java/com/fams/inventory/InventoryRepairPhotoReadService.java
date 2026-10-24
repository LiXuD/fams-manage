package com.fams.inventory;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.repair.InventoryRepairPort;

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
@Transactional
public class InventoryRepairPhotoReadService {
    private final InventoryRepairPort repairs;
    private final AttachmentEvidencePort attachments;
    private final JdbcTemplate db;
    private final ObjectMapper json;

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

    public AttachmentGrantSigner.Grant grant(
            Actor actor, long repair, long observation, UUID object) {
        return attachments.grant(
                "INVENTORY_EVENT",
                authorize(actor, repair, observation, object),
                identity(actor, object));
    }

    public java.util.List<java.util.Map<String, Object>> list(
            Actor actor, long repair, long observation) {
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
                                        .anyMatch(
                                                id ->
                                                        id.toString()
                                                                .equals(
                                                                        row.get("objectId")
                                                                                .toString())))
                .toList();
    }

    public AttachmentReadService.Content read(
            Actor actor, long repair, long observation, UUID object, String token)
            throws IOException {
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
