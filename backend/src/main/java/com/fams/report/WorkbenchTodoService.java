package com.fams.report;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class WorkbenchTodoService {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;

    WorkbenchTodoService(JdbcTemplate db, RequisitionActorContext actors) {
        this.db = db;
        this.actors = actors;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(RequisitionActorContext.Actor actor, Scope scope, Long before, int pageSize) {
        var current = actors.revalidateLocked(actor);
        if (!scope.allowed(current)) {
            throw new Forbidden();
        }
        var rows = db.query("""
                SELECT id,business_type,business_id,todo_type,opened_at
                FROM fams_todo
                WHERE todo_status='OPEN' AND closed_at IS NULL
                """ + scope.predicate + " AND (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
                (row, index) -> new Todo(row.getString("id"), row.getString("business_type"),
                        row.getString("business_id"), row.getString("todo_type"), row.getTimestamp("opened_at").toInstant()),
                scope == Scope.MINE ? current.id() : scope.name(), before, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).id());
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
    record Page(List<Todo> items, String nextBefore) {}
}
