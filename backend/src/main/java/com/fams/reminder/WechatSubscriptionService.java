package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.masterdata.NotificationChannelConfigPort;
import com.fams.product.contract.model.MiniSubscriptionVisibility;
import com.fams.product.contract.model.WechatSubscriptionGrant;
import com.fams.product.contract.model.WechatSubscriptionGrantRequest;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class WechatSubscriptionService {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final NotificationChannelConfigPort channels;
    private final Clock clock;

    WechatSubscriptionService(JdbcTemplate db, RequisitionActorContext actors,
            NotificationChannelConfigPort channels, Clock clock) {
        this.db = db;
        this.actors = actors;
        this.channels = channels;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    MiniSubscriptionVisibility visibility(RequisitionActorContext.Actor actor) {
        actors.revalidateLocked(actor);
        return new MiniSubscriptionVisibility().wechatTemplate(channels.currentTemplates().wechatTemplate());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    WechatSubscriptionGrant register(RequisitionActorContext.Actor actor, UUID key,
            WechatSubscriptionGrantRequest request) {
        var current = actors.revalidateLocked(actor);
        var existing = db.query("""
                SELECT id,template_id,scene,business_type,business_id,status,created_at
                FROM fams_wechat_subscription_grant WHERE account_id=? AND request_key=?
                """, (row, index) -> view(row.getLong(1), row.getString(2), row.getString(3),
                row.getString(4), row.getLong(5), row.getString(6), row.getTimestamp(7)),
                current.id(), key.toString());
        String scene = request.getScene().getValue();
        String businessType = request.getBusinessType().getValue();
        long businessId = Long.parseLong(request.getBusinessId());
        if (!existing.isEmpty()) {
            var prior = existing.getFirst();
            if (!prior.getTemplateId().equals(request.getTemplateId()) || !prior.getScene().getValue().equals(scene)
                    || !prior.getBusinessType().getValue().equals(businessType)
                    || !prior.getBusinessId().equals(request.getBusinessId())) {
                throw new ReminderFailure(409, "WECHAT_SUBSCRIPTION_KEY_CONFLICT");
            }
            return prior;
        }
        String template = channels.currentTemplates().wechatTemplate();
        if (template.isBlank() || !template.equals(request.getTemplateId())) {
            throw new ReminderFailure(409, "WECHAT_SUBSCRIPTION_TEMPLATE_UNCONFIGURED");
        }
        if (!sceneMatches(scene, businessType)) {
            throw new ReminderFailure(400, "WECHAT_SUBSCRIPTION_REQUEST_INVALID");
        }
        if (!owns(current, businessType, businessId)) {
            throw new ReminderFailure(403, "WECHAT_SUBSCRIPTION_FORBIDDEN");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update("""
                INSERT INTO fams_wechat_subscription_grant
                    (account_id,template_id,scene,business_type,business_id,status,request_key,created_at)
                VALUES (?,?,?,?,?,'AVAILABLE',?,?)
                """, current.id(), request.getTemplateId(), scene, businessType, businessId, key.toString(),
                Timestamp.from(now));
        long id = Objects.requireNonNull(db.queryForObject(
                "SELECT id FROM fams_wechat_subscription_grant WHERE account_id=? AND request_key=?",
                Long.class, current.id(), key.toString()), "Inserted subscription grant must be readable.");
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'WECHAT_SUBSCRIPTION_GRANTED','WECHAT_SUBSCRIPTION_GRANT',?,?,
                    JSON_OBJECT('scene',?,'businessType',?,'businessId',?,'templateId',?))
                """, actor.requestId().toString(), current.id(), current.roles().iterator().next().name(),
                Long.toString(id), Timestamp.from(now), scene, businessType, Long.toString(businessId),
                request.getTemplateId());
        return view(id, request.getTemplateId(), scene, businessType, businessId, "AVAILABLE", Timestamp.from(now));
    }

    private static boolean sceneMatches(String scene, String businessType) {
        return switch (scene) {
            case "PROGRESS" -> List.of("REQUISITION", "LOAN", "REPAIR").contains(businessType);
            case "RETURN" -> "LOAN".equals(businessType);
            case "INVENTORY_TASK" -> "INVENTORY_TASK".equals(businessType);
            case "RECEIPT" -> List.of("REQUISITION_ALLOCATION", "TRANSFER_ITEM", "INBOUND").contains(businessType);
            default -> false;
        };
    }

    private boolean owns(RequisitionActorContext.Current current, String businessType, long businessId) {
        String sql = switch (businessType) {
            case "REQUISITION" -> "SELECT id FROM fams_requisition WHERE id=? AND creator_account_id=?";
            case "LOAN" -> "SELECT id FROM fams_loan WHERE id=? AND borrower_employee_id=?";
            case "REPAIR" -> "SELECT id FROM fams_repair_request WHERE id=? AND creator_account_id=?";
            case "INVENTORY_TASK" -> "SELECT id FROM fams_inventory_item WHERE task_id=? AND executor_account_id=?";
            case "REQUISITION_ALLOCATION" -> """
                    SELECT x.id FROM fams_requisition_allocation x
                    JOIN fams_requisition_demand d ON d.id=x.demand_id
                    WHERE x.id=? AND COALESCE(x.receipt_target_employee_id,d.target_employee_id,
                        d.target_department_employee_id)=?
                    """;
            case "TRANSFER_ITEM" -> "SELECT id FROM fams_transfer_item WHERE id=? AND receiver_employee_id=?";
            case "INBOUND" -> """
                    SELECT id FROM fams_inbound_processing_ownership
                    WHERE receipt_id=? AND processor_account_id=? AND archived_at IS NULL
                    """;
            default -> throw new ReminderFailure(400, "WECHAT_SUBSCRIPTION_REQUEST_INVALID");
        };
        boolean byAccount = List.of("REQUISITION", "REPAIR", "INVENTORY_TASK", "INBOUND").contains(businessType);
        return !db.queryForList(sql, businessId, byAccount ? current.id() : current.employeeId()).isEmpty();
    }

    private static WechatSubscriptionGrant view(long id, String templateId, String scene, String businessType,
            long businessId, String status, Timestamp createdAt) {
        return new WechatSubscriptionGrant()
                .id(Long.toString(id))
                .templateId(templateId)
                .scene(WechatSubscriptionGrant.SceneEnum.fromValue(scene))
                .businessType(WechatSubscriptionGrant.BusinessTypeEnum.fromValue(businessType))
                .businessId(Long.toString(businessId))
                .status(WechatSubscriptionGrant.StatusEnum.fromValue(status))
                .createdAt(OffsetDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.UTC));
    }
}
