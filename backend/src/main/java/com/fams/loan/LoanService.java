package com.fams.loan;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.business.BorrowRequestDatePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/** Dates change only through one administrator's decision; overdue never mutates asset custody. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class LoanService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final BorrowRequestDatePort requests;

    LoanService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, BorrowRequestDatePort requests) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.requests = requests;
    }

    Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String scope = scope(current);
        long total = db.queryForObject("SELECT COUNT(*) FROM fams_loan l WHERE " + scope, Long.class);
        var rows = db.queryForList("SELECT l.*,a.asset_code,a.name,a.lifecycle_status,a.exception_status,a.version AS current_asset_version"
                + " FROM fams_loan l JOIN fams_asset a ON a.id=l.asset_id WHERE " + scope
                + " ORDER BY l.id DESC LIMIT ? OFFSET ?", size, (page - 1) * size);
        return Map.of("items", rows.stream().map(this::loanView).toList(), "total", total,
                "page", page, "pageSize", size);
    }

    Map<String, Object> eligibleRequests(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var result = new LinkedHashMap<>(requests.eligible(current.id(), current.has(Role.ASSET_ADMIN),
                current.has(Role.WAREHOUSE_OPERATOR), page, size));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) result.get("items");
        result.put("items", rows.stream().map(this::clean).toList());
        return result;
    }

    Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var loan = loan(id);
        visible(current, loan);
        loan.putAll(one("SELECT asset_code,name,lifecycle_status,exception_status,version AS current_asset_version"
                + " FROM fams_asset WHERE id=?", loan.get("asset_id")));
        var result = new LinkedHashMap<>(loanView(loan));
        result.put("extensions", db.queryForList("SELECT * FROM fams_loan_extension WHERE loan_id=? ORDER BY id", id)
                .stream().map(this::clean).toList());
        result.put("reminders", db.queryForList("SELECT * FROM fams_loan_reminder WHERE loan_id=? ORDER BY id", id)
                .stream().map(this::clean).toList());
        return result;
    }

    Map<String, Object> extensions(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String predicate = current.has(Role.ASSET_ADMIN) ? "1=1" : "proposed_by=" + current.id();
        var rows = db.queryForList("SELECT * FROM fams_loan_extension WHERE " + predicate
                + " ORDER BY id DESC LIMIT ? OFFSET ?", size, (page - 1) * size);
        long total = db.queryForObject("SELECT COUNT(*) FROM fams_loan_extension WHERE " + predicate, Long.class);
        return Map.of("items", rows.stream().map(this::clean).toList(), "total", total,
                "page", page, "pageSize", size);
    }

    Map<String, Object> propose(Actor actor, long id, boolean beforeReceipt, long version,
            String key, LoanController.Extension input) {
        var current = actors.revalidateLocked(actor);
        String hash = digest("extend:" + id + ":" + beforeReceipt + ":" + version, input);
        var target = beforeReceipt ? requestSnapshot(id) : loan(id);
        if (beforeReceipt) {
            var owners = db.queryForList("SELECT processor_account_id FROM fams_requisition_ownership"
                    + " WHERE request_id=? AND archived_at IS NULL", Long.class, id);
            Long owner = owners.isEmpty() ? null : owners.getFirst();
            if (!current.has(Role.ASSET_ADMIN) && current.id() != number(target, "creator_account_id")
                    && (owner == null || current.id() != owner || !current.has(Role.WAREHOUSE_OPERATOR))) {
                throw new LoanFailure("LOAN_FORBIDDEN", 403);
            }
        } else {
            visible(current, target);
            if (current.employeeId() != number(target, "borrower_employee_id")
                    && !current.has(Role.ASSET_ADMIN)) {
                throw new LoanFailure("LOAN_FORBIDDEN", 403);
            }
        }
        var replay = replay(current, "propose", key, hash);
        if (replay != null) { return replay; }
        target = target(id, beforeReceipt);
        checkVersion(target, version);
        LocalDate old = date(target, "expected_return_date");
        if (!input.expectedReturnDate().isAfter(old) || !input.expectedReturnDate().isAfter(today(target))) {
            throw new LoanFailure("LOAN_RETURN_DATE_INVALID", 400);
        }
        db.update("INSERT INTO fams_loan_extension (loan_id,request_id,target_version,original_due_date,"
                + "requested_due_date,reason,proposed_by,created_at) VALUES (?,?,?,?,?,?,?,?)",
                beforeReceipt ? null : id, beforeReceipt ? id : null, version, old,
                input.expectedReturnDate(), input.reason(), current.id(), now());
        long extension = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO fams_todo (pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('ASSET_ADMIN','LOAN_EXTENSION',?,'APPROVAL','OPEN',?)", extension, now());
        audit(actor, current, "LOAN_EXTENSION_PROPOSED", extension, null, 0L,
                Map.of("reason", input.reason(), "originalReturnDate", old.toString(),
                        "requestedReturnDate", input.expectedReturnDate().toString()));
        return finish(current, "propose", key, hash, extension);
    }

    Map<String, Object> decide(Actor actor, long id, long version, String key, LoanController.Decision input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) { throw new LoanFailure("LOAN_FORBIDDEN", 403); }
        String hash = digest("decision:" + id + ":" + version, input);
        var replay = replay(current, "decision", key, hash);
        if (replay != null) { return replay; }
        var extension = one("SELECT * FROM fams_loan_extension WHERE id=? FOR UPDATE", id);
        checkVersion(extension, version);
        if (!"PENDING".equals(extension.get("extension_status"))) { throw new LoanFailure("LOAN_ALREADY_DECIDED", 409); }
        boolean beforeReceipt = extension.get("loan_id") == null;
        long targetId = number(extension, beforeReceipt ? "request_id" : "loan_id");
        var target = input.approved() ? target(targetId, beforeReceipt)
                : beforeReceipt ? requestSnapshot(targetId) : loan(targetId);
        LocalDate today = today(target), old = date(extension, "original_due_date");
        if (input.approved()) {
            checkVersion(target, number(extension, "target_version"));
            if (!old.equals(date(target, "expected_return_date"))
                    || !date(extension, "requested_due_date").isAfter(today)) {
                throw new LoanFailure("LOAN_TARGET_CHANGED", 409);
            }
            if (beforeReceipt) {
                requests.extend(targetId, number(extension, "target_version"), date(extension, "requested_due_date"), clock.instant());
            } else {
                db.update("UPDATE fams_loan SET expected_return_date=?,version=version+1,updated_at=? WHERE id=?",
                        date(extension, "requested_due_date"), now(), targetId);
            }
        }
        db.update("UPDATE fams_loan_extension SET extension_status=?,decided_by=?,decision_reason=?,"
                + "decided_business_date=?,overdue_from=?,overdue_through=?,decided_at=?,version=version+1 WHERE id=?",
                input.approved() ? "APPROVED" : "REJECTED", current.id(), input.reason(), today,
                old.isBefore(today) ? old.plusDays(1) : null, old.isBefore(today) ? today : null, now(), id);
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='LOAN_EXTENSION'"
                + " AND business_id=? AND closed_at IS NULL", now(), id);
        audit(actor, current, input.approved() ? "LOAN_EXTENSION_APPROVED" : "LOAN_EXTENSION_REJECTED",
                id, version, version + 1, Map.of("reason", input.reason(), "targetId", Long.toString(targetId)));
        return finish(current, "decision", key, hash, id);
    }

    private Map<String, Object> requestSnapshot(long id) {
        try { return requests.snapshot(id); }
        catch (IllegalStateException failure) {
            throw new LoanFailure(failure.getMessage(), "LOAN_TARGET_NOT_FOUND".equals(failure.getMessage()) ? 404 : 409);
        }
    }

    private void audit(Actor actor, Current current, String action, long id, Long before, Long after,
            Map<String, Object> detail) {
        String role = current.has(Role.ASSET_ADMIN) ? Role.ASSET_ADMIN.name()
                : current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json)"
                + " VALUES (?,?,?,?,'LOAN_EXTENSION',?,?,?,?,?)", actor.requestId().toString(), current.id(),
                role, action, id, before, after, now(), json.writeValueAsString(detail));
    }

    private Map<String, Object> target(long id, boolean beforeReceipt) {
        if (beforeReceipt) {
            try { return requests.outstanding(id); }
            catch (IllegalStateException failure) { throw new LoanFailure(failure.getMessage(), 409); }
        }
        var loan = loan(id);
        if (!"OPEN".equals(loan.get("relation_status"))) { throw new LoanFailure("LOAN_CLOSED", 409); }
        String exception = db.queryForObject("SELECT exception_status FROM fams_asset WHERE id=? FOR UPDATE",
                String.class, number(loan, "asset_id"));
        if ("LOST".equals(exception)) { throw new LoanFailure("LOAN_LOST_LIABILITY", 409); }
        return loan;
    }

    private Map<String, Object> loan(long id) {
        return one("SELECT * FROM fams_loan WHERE id=? FOR UPDATE", id);
    }

    private void visible(Current current, Map<String, Object> loan) {
        if (!current.globalApplicant() && current.employeeId() != number(loan, "borrower_employee_id")
                && !(current.has(Role.DEPARTMENT_ASSET_MANAGER)
                    && current.organizationId() == number(loan, "using_organization_id"))) {
            throw new LoanFailure("LOAN_NOT_FOUND", 404);
        }
    }

    private String scope(Current current) {
        if (current.globalApplicant()) { return "1=1"; }
        return "l.borrower_employee_id=" + current.employeeId()
                + (current.has(Role.DEPARTMENT_ASSET_MANAGER) ? " OR l.using_organization_id=" + current.organizationId() : "");
    }

    private Map<String, Object> loanView(Map<String, Object> loan) {
        var result = new LinkedHashMap<>(clean(loan));
        boolean open = "OPEN".equals(loan.get("relation_status")) && !"SCRAPPED".equals(loan.get("lifecycle_status"));
        boolean lost = "LOST".equals(loan.get("exception_status"));
        result.put("overdue", open && !lost && date(loan, "expected_return_date").isBefore(today(loan)));
        result.put("lostLiability", open && lost);
        return result;
    }

    private Map<String, Object> replay(Current current, String action, String key, String hash) {
        var rows = db.queryForList("SELECT * FROM fams_idempotency_record WHERE actor_account_id=?"
                + " AND operation_id=? AND idempotency_key=? FOR UPDATE", current.id(), "loan:" + action, key);
        if (rows.isEmpty()) { return null; }
        var row = rows.getFirst();
        Timestamp expiry = db.queryForObject("SELECT expires_at FROM fams_idempotency_record WHERE id=?",
                Timestamp.class, row.get("id"));
        if (!expiry.toInstant().isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", row.get("id"));
            return null;
        }
        if (!hash.equals(row.get("payload_sha256"))) { throw new LoanFailure("LOAN_IDEMPOTENCY_CONFLICT", 409); }
        // Each response is owned by the currently revalidated proposer or administrator account.
        return json.readValue(row.get("response_body_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(Current current, String action, String key, String hash, long id) {
        var result = clean(one("SELECT * FROM fams_loan_extension WHERE id=?", id));
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,idempotency_key,"
                + "payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,"
                + "resource_version,created_at,completed_at,expires_at)"
                + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'LOAN_EXTENSION',?,?,?,?,?)",
                current.id(), "loan:" + action, key, hash, json.writeValueAsString(result), id,
                result.get("version"), now(), now(), Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) { throw new LoanFailure("LOAN_NOT_FOUND", 404); }
        return rows.getFirst();
    }

    private void checkVersion(Map<String, Object> row, long version) {
        if (number(row, "version") != version) { throw new LoanFailure("LOAN_VERSION_CONFLICT", 409); }
    }

    private LocalDate today(Map<String, Object> row) {
        var zone = ZoneId.of(json.readTree(row.get("parameter_snapshot_json").toString()).get("timezone").asText());
        return clock.instant().atZone(zone).toLocalDate();
    }

    private LocalDate date(Map<String, Object> row, String key) { return LocalDate.parse(row.get(key).toString()); }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Timestamp now() { return Timestamp.from(clock.instant()); }

    private String digest(String prefix, Object body) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((prefix + "\n" + json.writeValueAsString(body)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if ("parameter_snapshot_json".equals(key) || "pending_target".equals(key)) { return; }
            String[] parts = key.split("_");
        StringBuilder name = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) { name.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); }
            Object wire = value;
            if (value instanceof java.time.LocalDateTime time) { wire = time.toInstant(java.time.ZoneOffset.UTC).toString(); }
            else if (value instanceof Timestamp time) { wire = time.toInstant().toString(); }
            else if (value instanceof java.time.LocalDate || value instanceof java.sql.Date) { wire = value.toString(); }
            else if (value instanceof Number && (key.equals("id") || key.endsWith("_id") || key.equals("proposed_by") || key.equals("decided_by"))) { wire = value.toString(); }
            result.put(name.toString(), wire);
        });
        return result;
    }
}
