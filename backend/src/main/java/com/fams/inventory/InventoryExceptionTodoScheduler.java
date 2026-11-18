package com.fams.inventory;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class InventoryExceptionTodoScheduler {
    private final JdbcTemplate db;
    private final Clock clock;

    InventoryExceptionTodoScheduler(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void closeResolved() {
        db.update("""
                UPDATE fams_todo todo
                JOIN fams_inventory_observation o ON o.id=todo.business_id
                JOIN fams_inventory_task t ON t.id=o.task_id
                JOIN fams_inventory_item i ON i.id=o.item_id
                LEFT JOIN fams_inventory_item_review r
                    ON r.item_id=i.id AND r.recheck_generation=o.recheck_generation
                SET todo.todo_status='CLOSED',todo.closed_at=?
                WHERE todo.business_type='INVENTORY_OBSERVATION' AND todo.todo_status='OPEN'
                    AND todo.closed_at IS NULL
                    AND (t.task_status NOT IN ('IN_PROGRESS','WAITING_REVIEW')
                        OR i.recheck_generation<>o.recheck_generation
                        OR r.review_status='ACCEPTED')
                """, Timestamp.from(clock.instant()));
    }
}
