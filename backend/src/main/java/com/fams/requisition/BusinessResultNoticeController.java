package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Results belong to the responsible employee; reading cannot alter asset responsibility. */
@RestController
@Profile("fams-product")
@Validated
@Transactional(isolation = Isolation.READ_COMMITTED)
@RequestMapping("/api/v1/business-result-notices")
class BusinessResultNoticeController {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final ObjectMapper json;

    BusinessResultNoticeController(
            JdbcTemplate db, RequisitionActorContext actors, ObjectMapper json) {
        this.db = db;
        this.actors = actors;
        this.json = json;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        var current = actors.revalidateLocked(actors.capture());
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

    @PostMapping("/{id}/read")
    Map<String, Object> read(@PathVariable @Min(1) long id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        var current = actors.revalidateLocked(actors.capture());
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
