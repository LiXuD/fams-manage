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
    private static final String OPEN_TODOS = " FROM fams_todo WHERE todo_status='OPEN' AND closed_at IS NULL";
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;

    WorkbenchTodoService(JdbcTemplate db, RequisitionActorContext actors) {
        this.db = db;
        this.actors = actors;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(RequisitionActorContext.Actor actor, Scope scope, String stage, Long before, int pageSize) {
        var current = actors.revalidateLocked(actor);
        if (!scope.allowed(current)) {
            throw new Forbidden();
        }
        Object assigneeOrPool = scope == Scope.MINE ? current.id() : scope.name();
        String visible = OPEN_TODOS + scope.predicate;
        var counts = db.query("SELECT todo_type,COUNT(*) AS total" + visible + " GROUP BY todo_type ORDER BY todo_type",
                (row, index) -> new StageCount(row.getString("todo_type"), row.getLong("total")), assigneeOrPool);
        var rows = db.query("SELECT id,business_type,business_id,todo_type,opened_at" + visible
                        + " AND (? IS NULL OR todo_type=?) AND (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
                (row, index) -> new Todo(row.getString("id"), row.getString("business_type"),
                        row.getString("business_id"), row.getString("todo_type"), row.getTimestamp("opened_at").toInstant()),
                assigneeOrPool, stage, stage, before, before, pageSize + 1);
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
