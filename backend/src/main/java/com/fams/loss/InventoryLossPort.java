package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The loss owner creates and reads reports tied to immutable inventory missing reasons. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryLossPort {
    public record Source(long taskId, long itemId, long reasonId, long recheckGeneration) {}

    public record Progress(String reportId, String status, String processorAccountId, LocalDate nextReviewDate) {
        public boolean transferred() {
            return processorAccountId != null && nextReviewDate != null
                    && List.of("SEARCHING", "CONFIRMED_LOST", "RECOVERED").contains(status);
        }

        public boolean canRetry() {
            return "REJECTED".equals(status) || "CANCELLED".equals(status);
        }
    }

    private final JdbcTemplate db;
    private final LossService reports;

    public InventoryLossPort(JdbcTemplate db, LossService reports) {
        this.db = db;
        this.reports = reports;
    }

    public String submit(Actor actor, Source source, LossInput.Submission input) {
        String report = reports.submitInventory(actor, source, input);
        db.update("INSERT INTO fams_loss_inventory_source(report_id,reason_id) VALUES (?,?)",
                Long.parseLong(report), source.reasonId());
        return report;
    }

    public Map<Long, Progress> progress(List<Long> reasons) {
        var result = new HashMap<Long, Progress>();
        var query = new NamedParameterJdbcTemplate(db);
        for (int offset = 0; offset < reasons.size(); offset += 200) {
            query.query("""
                    SELECT s.reason_id,r.id,r.report_status,r.processor_account_id,r.next_review_date
                    FROM fams_loss_inventory_source s
                    JOIN fams_loss_report r ON r.id=s.report_id
                    WHERE s.reason_id IN (:reasons)
                    ORDER BY s.reason_id,r.id
                    """,
                    Map.of("reasons", reasons.subList(offset, Math.min(offset + 200, reasons.size()))),
                    row -> {
                        result.put(row.getLong("reason_id"), new Progress(row.getString("id"),
                                row.getString("report_status"), row.getString("processor_account_id"),
                                row.getObject("next_review_date", LocalDate.class)));
                    });
        }
        return result;
    }
}
