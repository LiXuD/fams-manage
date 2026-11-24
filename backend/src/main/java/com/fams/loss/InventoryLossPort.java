package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Actor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(InventoryLossPort.class);
    /**
     * Identifies the inventory missing-reason row that a loss report is created from: the counting
     * task and item being resolved plus the immutable reason id and its recheck generation.
     */
    public record Source(long taskId, long itemId, long reasonId, long recheckGeneration) {}

    /**
     * Loss-report progress joined back onto an inventory reason, used to render whether the reason
     * has been handed over to the loss workflow and whether resubmission is allowed.
     */
    public record Progress(String reportId, String status, String processorAccountId, LocalDate nextReviewDate) {
        /**
         * Reports whether the reason has effectively transferred to loss handling: a processor is
         * assigned, a next review date exists, and the report is in SEARCHING, CONFIRMED_LOST or
         * RECOVERED status.
         *
         * @return true when the inventory reason is considered owned by the loss report
         */
        public boolean transferred() {
            return processorAccountId != null && nextReviewDate != null
                    && List.of("SEARCHING", "CONFIRMED_LOST", "RECOVERED").contains(status);
        }

        /**
         * Reports whether a new loss submission may be attempted for this reason, which is only the
         * case when the previous report ended as REJECTED or CANCELLED.
         *
         * @return true when resubmission is allowed
         */
        public boolean canRetry() {
            return "REJECTED".equals(status) || "CANCELLED".equals(status);
        }
    }

    private final JdbcTemplate db;
    private final LossService reports;

    InventoryLossPort(JdbcTemplate db, LossService reports) {
        this.db = db;
        this.reports = reports;
    }

    /**
     * Creates a loss report for an inventory missing reason through {@link LossService} and records
     * the report-to-reason link in {@code fams_loss_inventory_source} so later progress queries can
     * join back. Runs inside the caller's mandatory transaction.
     *
     * @param actor requisition actor context captured for permission and identity checks
     * @param source inventory reason the report is submitted from
     * @param input validated submission payload for the loss report
     * @return id of the created loss report as a string
     * @throws LossFailure if the underlying submission is rejected (for example duplicate or
     *         invalid source data)
     */
    public String submit(Actor actor, Source source, LossInput.Submission input) {
        String report = reports.submitInventory(actor, source, input);
        db.update("INSERT INTO fams_loss_inventory_source(report_id,reason_id) VALUES (?,?)",
                Long.parseLong(report), source.reasonId());
        LOG.debug("INSERT fams_loss_inventory_source reason {} task {}.", source.reasonId(), source.taskId());
        return report;
    }

    /**
     * Looks up the latest loss-report progress for the given inventory reason ids in batches of
     * 200, joining {@code fams_loss_inventory_source} with {@code fams_loss_report}.
     *
     * @param reasons inventory missing-reason ids to resolve progress for
     * @return map from reason id to its loss-report progress; reasons without a report are absent
     */
    public Map<Long, Progress> progress(List<Long> reasons) {
        LOG.debug("Loss progress queried for submitted reasons.");
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
