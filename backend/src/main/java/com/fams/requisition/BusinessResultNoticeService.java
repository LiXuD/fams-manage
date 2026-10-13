package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class BusinessResultNoticeService {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final ObjectMapper json;

    BusinessResultNoticeService(JdbcTemplate db, RequisitionActorContext actors, ObjectMapper json) {
        this.db = db;
        this.actors = actors;
        this.json = json;
    }

    Map<String, Object> list(
            RequisitionActorContext.Actor actor, int page, int pageSize) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT id,business_type,business_id,item_id,content_json,"
                + "created_at,read_at FROM fams_business_result_notice WHERE recipient_employee_id=?"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", current.employeeId(), pageSize,
                (page - 1) * pageSize);
        var items = rows.stream().map(row -> Map.of(
                "id", row.get("id"), "businessType", row.get("business_type"),
                "businessId", row.get("business_id"), "itemId", row.get("item_id"),
                "content", json.readTree(row.get("content_json").toString()),
                "createdAt", row.get("created_at").toString(),
                "read", row.get("read_at") != null)).toList();
        long total = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=?", Long.class, current.employeeId());
        long unread = db.queryForObject("SELECT COUNT(*) FROM fams_business_result_notice"
                + " WHERE recipient_employee_id=? AND read_at IS NULL", Long.class,
                current.employeeId());
        return Map.of("items", items, "page", page, "pageSize", pageSize,
                "total", total, "unread", unread);
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
}
