package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
class BusinessResultNoticeService {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessResultNoticeService.class);

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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NoticeViews.NoticePage list(
            RequisitionActorContext.Actor actor, int page, int pageSize) {
        LOG.debug("Listing business result notices actor={} page={} pageSize={}", actor.id(), page, pageSize);
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT id,business_type,business_id,item_id,content_json,"
                + "created_at,read_at,EXISTS (SELECT 1 FROM fams_audit_event e WHERE"
                + " e.resource_type='BUSINESS_RESULT_NOTICE' AND e.resource_id=n.id"
                + " AND e.action='RESULT_NOTICE_OBJECTION') AS review_requested"
                + " FROM fams_business_result_notice n WHERE recipient_employee_id=?"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", current.employeeId(), pageSize,
                (page - 1) * pageSize);
        var items = rows.stream().map(row -> NoticeViews.noticeItem(json, row)).toList();
        Long total = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=?", Long.class, current.employeeId());
        Long unread = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=? AND read_at IS NULL", Long.class,
                current.employeeId());
        return new NoticeViews.NoticePage(items, page, pageSize,
                java.util.Objects.requireNonNull(total), java.util.Objects.requireNonNull(unread));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NoticeViews.ReviewRequestPage reviewRequests(RequisitionActorContext.Actor actor, int page, int pageSize) {
        LOG.debug("Listing notice review requests actor={} page={} pageSize={}", actor.id(), page, pageSize);
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
                + " WHERE n.execution_type='PROXY_CONFIRMATION' AND n.business_type IN ('REQUISITION','TRANSFER','REPAIR')";
        var rows = db.queryForList("SELECT n.id,n.execution_id,n.business_id,n.business_type,r.name AS recipient,"
                + "a.detail_json,a.occurred_at" + from + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                pageSize, (page - 1) * pageSize);
        var items = rows.stream().map(row -> NoticeViews.reviewRequestItem(json, row)).toList();
        Long total = db.queryForObject("SELECT COUNT(*)" + from, Long.class);
        return new NoticeViews.ReviewRequestPage(items, java.util.Objects.requireNonNull(total), page, pageSize);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NoticeViews.ReadReceipt read(RequisitionActorContext.Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT id FROM fams_business_result_notice"
                + " WHERE id=? AND recipient_employee_id=? FOR UPDATE", id, current.employeeId());
        if (rows.isEmpty()) {
            throw RequisitionFailure.unavailable();
        }
        db.update("UPDATE fams_business_result_notice SET read_at=COALESCE(read_at,"
                + "CURRENT_TIMESTAMP(3)) WHERE id=? AND recipient_employee_id=?", id,
                current.employeeId());
        LOG.info("Marked business result notice read actor={} noticeId={}", current.id(), id);
        return new NoticeViews.ReadReceipt(Long.toString(id), true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NoticeViews.ObjectionReceipt objection(RequisitionActorContext.Actor actor, long id, String key, String reason) {
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
            return json.readValue(prior.getFirst().get("response_body_json").toString(),
                    NoticeViews.ObjectionReceipt.class);
        }
        var notice = rows.getFirst();
        var result = new NoticeViews.ObjectionReceipt(Long.toString(id), "ADMIN_REVIEW_REQUESTED",
                (String) notice.get("business_type"), notice.get("business_id").toString());
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
        LOG.info("Raised admin review objection on business result notice actor={} noticeId={} businessType={}",
                current.id(), id, notice.get("business_type"));
        return result;
    }

}
