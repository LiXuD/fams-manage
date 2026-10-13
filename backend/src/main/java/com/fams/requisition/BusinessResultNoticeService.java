package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class BusinessResultNoticeService {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final ObjectMapper json;
    private final Clock clock;

    BusinessResultNoticeService(JdbcTemplate db, RequisitionActorContext actors, ObjectMapper json, Clock clock) {
        this.db = db;
        this.actors = actors;
        this.json = json;
        this.clock = clock;
    }

    Map<String, Object> list(
            RequisitionActorContext.Actor actor, int page, int pageSize) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT id,business_type,business_id,item_id,content_json,"
                + "created_at,read_at,EXISTS (SELECT 1 FROM fams_audit_event e WHERE"
                + " e.resource_type='BUSINESS_RESULT_NOTICE' AND e.resource_id=n.id"
                + " AND e.action='RESULT_NOTICE_OBJECTION') AS review_requested"
                + " FROM fams_business_result_notice n WHERE recipient_employee_id=?"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", current.employeeId(), pageSize,
                (page - 1) * pageSize);
        var items = rows.stream().map(row -> Map.of(
                "id", row.get("id"), "businessType", row.get("business_type"),
                "businessId", row.get("business_id"), "itemId", row.get("item_id"),
                "content", json.readTree(row.get("content_json").toString()),
                "createdAt", row.get("created_at").toString(),
                "read", row.get("read_at") != null, "reviewRequested",
                "1".equals(String.valueOf(row.get("review_requested")))
                        || Boolean.TRUE.equals(row.get("review_requested")))).toList();
        Long total = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=?", Long.class, current.employeeId());
        Long unread = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=? AND read_at IS NULL", Long.class,
                current.employeeId());
        return Map.of("items", items, "page", page, "pageSize", pageSize,
                "total", java.util.Objects.requireNonNull(total), "unread", java.util.Objects.requireNonNull(unread));
    }

    Map<String, Object> reviewRequests(RequisitionActorContext.Actor actor, int page, int pageSize) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw RequisitionFailure.forbidden();
        }
        String from = " FROM fams_business_result_notice n"
                + " JOIN fams_employee r ON r.id=n.recipient_employee_id"
                + " JOIN fams_todo t ON t.business_type=n.business_type AND t.business_id=n.business_id"
                + " AND t.todo_type='PROXY_RECEIPT_REVIEW' AND t.closed_at IS NULL"
                + " JOIN fams_audit_event a ON a.resource_type='BUSINESS_RESULT_NOTICE'"
                + " AND a.resource_id=n.id AND a.action='RESULT_NOTICE_OBJECTION'"
                + " AND a.id=(SELECT MAX(x.id) FROM fams_audit_event x"
                + " WHERE x.resource_type='BUSINESS_RESULT_NOTICE' AND x.resource_id=n.id"
                + " AND x.action='RESULT_NOTICE_OBJECTION')"
                + " WHERE n.execution_type='PROXY_CONFIRMATION' AND n.business_type='REQUISITION'";
        var rows = db.queryForList("SELECT n.id,n.execution_id,n.business_id,r.name AS recipient,"
                + "a.detail_json,a.occurred_at" + from + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                pageSize, (page - 1) * pageSize);
        var items = rows.stream().map(row -> Map.of(
                "noticeId", row.get("id").toString(),
                "executionId", row.get("execution_id").toString(),
                "businessId", row.get("business_id").toString(),
                "recipient", row.get("recipient"),
                "reason", json.readTree(row.get("detail_json").toString()).path("reason").asText(),
                "createdAt", row.get("occurred_at").toString())).toList();
        Long total = db.queryForObject("SELECT COUNT(*)" + from, Long.class);
        return Map.of("items", items, "total", java.util.Objects.requireNonNull(total),
                "page", page, "pageSize", pageSize);
    }

    Map<String, Object> read(RequisitionActorContext.Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT id FROM fams_business_result_notice"
                + " WHERE id=? AND recipient_employee_id=? FOR UPDATE", id, current.employeeId());
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        db.update("UPDATE fams_business_result_notice SET read_at=COALESCE(read_at,"
                + "CURRENT_TIMESTAMP(3)) WHERE id=? AND recipient_employee_id=?", id,
                current.employeeId());
        return Map.of("id", id, "read", true);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> objection(RequisitionActorContext.Actor actor, long id, String key, String reason) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT * FROM fams_business_result_notice"
                + " WHERE id=? AND recipient_employee_id=? FOR UPDATE", id, current.employeeId());
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + reason).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id=?"
                + " AND operation_id='requestBusinessResultReview' AND idempotency_key=? AND expires_at<=?",
                current.id(), key, clock.instant());
        var prior = db.queryForList("SELECT payload_sha256,response_body_json FROM fams_idempotency_record"
                + " WHERE actor_account_id=? AND operation_id='requestBusinessResultReview'"
                + " AND idempotency_key=? FOR UPDATE", current.id(), key);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                throw RequisitionFailure.conflict("RESULT_NOTICE_IDEMPOTENCY_CONFLICT");
            }
            return json.readValue(prior.getFirst().get("response_body_json").toString(), Map.class);
        }
        var notice = rows.getFirst();
        var result = Map.of("noticeId", Long.toString(id), "status", "ADMIN_REVIEW_REQUESTED",
                "businessType", notice.get("business_type"), "businessId", notice.get("business_id").toString());
        db.update("INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,business_id,"
                + "todo_type,todo_status,opened_at) VALUES ('ASSET_ADMIN',NULL,?,?,'PROXY_RECEIPT_REVIEW','OPEN',?)"
                + " ON DUPLICATE KEY UPDATE id=id", notice.get("business_type"), notice.get("business_id"), clock.instant());
        String actingRole = current.has(Role.EMPLOYEE) ? "EMPLOYEE"
                : current.has(Role.DEPARTMENT_ASSET_MANAGER) ? "DEPARTMENT_ASSET_MANAGER"
                : current.has(Role.WAREHOUSE_OPERATOR) ? "WAREHOUSE_OPERATOR" : "ASSET_ADMIN";
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,"
                + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                + " VALUES (?,?,?,'RESULT_NOTICE_OBJECTION','BUSINESS_RESULT_NOTICE',?,0,0,?,?)",
                actor.requestId().toString(), current.id(), actingRole, id, clock.instant(),
                json.writeValueAsString(Map.of("reason", reason, "executionId", notice.get("execution_id").toString(),
                        "businessId", notice.get("business_id").toString())));
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,idempotency_key,"
                + "payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,"
                + "resource_version,created_at,completed_at,expires_at)"
                + " VALUES (?,'requestBusinessResultReview',?,?,'SUCCEEDED',200,?,'BUSINESS_RESULT_NOTICE',?,0,?,?,?)",
                current.id(), key, hash, json.writeValueAsString(result), id, clock.instant(), clock.instant(),
                clock.instant().plus(24, ChronoUnit.HOURS));
        return result;
    }

}
