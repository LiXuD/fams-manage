package com.fams.report;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class WorkbenchTodoService {
    private static final String OPEN_TODOS = " FROM fams_todo WHERE todo_status='OPEN' AND closed_at IS NULL";
    private static final String REASSIGNMENT_OWNER = """
            AND todo_type='REASSIGNMENT_REQUIRED' AND CASE business_type
                WHEN 'INBOUND' THEN (SELECT o.processor_account_id FROM fams_inbound_processing_ownership o
                    WHERE o.receipt_id=fams_todo.business_id AND o.active_guard=1)
                WHEN 'REQUISITION' THEN (SELECT o.processor_account_id FROM fams_requisition_ownership o
                    WHERE o.request_id=fams_todo.business_id AND o.active_guard=1)
                WHEN 'TRANSFER' THEN (SELECT o.processor_account_id FROM fams_transfer o
                    WHERE o.id=fams_todo.business_id AND o.request_status IN ('PROCESSING','WAITING_HANDOVER'))
                WHEN 'ASSET_RETURN' THEN (SELECT o.processor_account_id FROM fams_asset_return o
                    WHERE o.id=fams_todo.business_id AND o.return_status='WAREHOUSE_PROCESSING')
                WHEN 'REPAIR' THEN (SELECT o.processor_account_id FROM fams_repair_request o
                    WHERE o.id=fams_todo.business_id AND o.repair_status='WAREHOUSE_PROCESSING')
                ELSE (SELECT owner.assignee_account_id FROM fams_todo owner
                    WHERE owner.business_type=fams_todo.business_type AND owner.business_id=fams_todo.business_id
                        AND owner.pool_type='WAREHOUSE_OPERATOR' AND owner.active_guard=1
                        AND owner.todo_status='OPEN' AND owner.assignee_account_id=? LIMIT 1)
            END=?
            """;
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;

    WorkbenchTodoService(JdbcTemplate db, RequisitionActorContext actors) {
        this.db = db;
        this.actors = actors;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(RequisitionActorContext.Actor actor, Scope scope, String stage, Long before,
            int pageSize, Long processorAccountId) {
        var current = actors.revalidateLocked(actor);
        if (!scope.allowed(current) || processorAccountId != null && scope != Scope.ASSET_ADMIN) {
            throw new Forbidden();
        }
        var parameters = new ArrayList<>();
        parameters.add(scope == Scope.MINE ? current.id() : scope.name());
        String visible = OPEN_TODOS + scope.predicate;
        if (processorAccountId != null) {
            visible += " " + REASSIGNMENT_OWNER;
            Collections.addAll(parameters, processorAccountId, processorAccountId);
        }
        var counts = db.query("SELECT todo_type,COUNT(*) AS total" + visible + " GROUP BY todo_type ORDER BY todo_type",
                (row, index) -> new StageCount(row.getString("todo_type"), row.getLong("total")), parameters.toArray());
        Collections.addAll(parameters, stage, stage, before, before, pageSize + 1);
        var rows = db.query("""
                SELECT id,IF(business_type='INVENTORY_OBSERVATION','INVENTORY_TASK',business_type) business_type,
                    IF(business_type='INVENTORY_OBSERVATION',
                        (SELECT o.task_id FROM fams_inventory_observation o WHERE o.id=fams_todo.business_id),
                        business_id) business_id,todo_type,opened_at
                """ + visible
                        + " AND (? IS NULL OR todo_type=?) AND (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
                (row, index) -> new Todo(row.getString("id"), row.getString("business_type"),
                        row.getString("business_id"), row.getString("todo_type"),
                        row.getTimestamp("opened_at").toInstant()),
                parameters.toArray());
        if (rows.size() <= pageSize) {
            return new Page(rows, null, counts);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).id(), counts);
    }

    enum Scope {
        ASSET_ADMIN(" AND pool_type=? AND assignee_account_id IS NULL"),
        WAREHOUSE_OPERATOR(" AND pool_type=? AND assignee_account_id IS NULL"),
        MINE(" AND assignee_account_id=?");

        private final String predicate;

        Scope(String predicate) {
            this.predicate = predicate;
        }

        boolean allowed(RequisitionActorContext.Current actor) {
            return switch (this) {
                case ASSET_ADMIN -> actor.has(Role.ASSET_ADMIN);
                case WAREHOUSE_OPERATOR -> actor.has(Role.WAREHOUSE_OPERATOR);
                case MINE -> actor.globalApplicant();
            };
        }
    }

    static final class Forbidden extends RuntimeException {}

    record Todo(String id, String businessType, String businessId, String stage, Instant openedAt) {}
    record StageCount(String stage, long count) {}
    record Page(List<Todo> items, String nextBefore, List<StageCount> stageCounts) {}
}
