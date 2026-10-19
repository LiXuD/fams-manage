package com.fams.inbound;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inventory may link a submitted receipt; it never gains the warehouse creator's draft rights. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryInboundPort {
    public record Context(
            String receiptId,
            long version,
            String status,
            String assetId,
            String assetCode,
            long assetVersion,
            String cardStage,
            String itemStatus,
            boolean accepted) {}

    private final JdbcTemplate db;
    private final RequisitionActorContext actors;

    public InventoryInboundPort(JdbcTemplate db, RequisitionActorContext actors) {
        this.db = db;
        this.actors = actors;
    }

    public Context find(
            RequisitionActorContext.Actor actor, String receiptNumber, String assetCode) {
        if (!actors.revalidateLocked(actor).has(Role.ASSET_ADMIN)) {
            throw new IllegalArgumentException("Inventory administrator required");
        }
        var rows =
                db.queryForList(
                        "SELECT r.id,i.asset_id FROM fams_inbound_receipt r JOIN"
                            + " fams_inbound_receipt_item i ON i.receipt_id=r.id JOIN fams_asset a"
                            + " ON a.id=i.asset_id WHERE r.receipt_no=? AND r.submitted_at IS NOT"
                            + " NULL AND a.asset_code=? LIMIT 1 FOR SHARE",
                        receiptNumber,
                        assetCode);
        return rows.isEmpty()
                ? null
                : context(
                        actor,
                        ((Number) rows.getFirst().get("id")).longValue(),
                        ((Number) rows.getFirst().get("asset_id")).longValue());
    }

    public Context context(RequisitionActorContext.Actor actor, long receiptId, long assetId) {
        if (!actors.revalidateLocked(actor).has(Role.ASSET_ADMIN)) {
            throw new IllegalArgumentException("Inventory administrator required");
        }
        var receipts =
                db.queryForList(
                        "SELECT id,version,receipt_status FROM fams_inbound_receipt"
                                + " WHERE id=? AND submitted_at IS NOT NULL FOR UPDATE",
                        receiptId);
        if (receipts.isEmpty()) {
            return null;
        }
        var lines =
                db.queryForList(
                        "SELECT a.id,a.asset_code,a.version,a.card_stage,i.execution_status,"
                            + " EXISTS(SELECT 1 FROM fams_inbound_acceptance x WHERE"
                            + " x.receipt_item_id=i.id AND x.asset_id=a.id) AS accepted FROM"
                            + " fams_inbound_receipt_item i JOIN fams_asset a ON a.id=i.asset_id"
                            + " WHERE i.receipt_id=? AND i.asset_id=? FOR UPDATE",
                        receiptId,
                        assetId);
        if (lines.size() != 1) {
            return null;
        }
        var receipt = receipts.getFirst();
        var line = lines.getFirst();
        return new Context(
                Long.toString(receiptId),
                ((Number) receipt.get("version")).longValue(),
                receipt.get("receipt_status").toString(),
                Long.toString(assetId),
                line.get("asset_code").toString(),
                ((Number) line.get("version")).longValue(),
                line.get("card_stage").toString(),
                line.get("execution_status").toString(),
                ((Number) line.get("accepted")).intValue() == 1);
    }
}
