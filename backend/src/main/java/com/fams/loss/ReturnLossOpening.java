package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.business.ReturnLossOpeningPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
class ReturnLossOpening implements ReturnLossOpeningPort {
    private final LossService losses;
    private final JdbcTemplate db;

    ReturnLossOpening(LossService losses, JdbcTemplate db) {
        this.losses = losses;
        this.db = db;
    }

    @Override
    public long open(Actor actor, Opening opening) {
        long reportId;
        try {
            reportId = losses.submitReturn(actor, opening);
        } catch (LossFailure failure) {
            throw new Failure(failure.getMessage(), failure.status());
        }
        db.update("INSERT INTO fams_loss_return_source(report_id,followup_id) VALUES (?,?)", reportId, opening.followupId());
        return reportId;
    }

    @Override
    public Map<Long, Summary> latest(List<Long> followupIds) {
        if (followupIds.isEmpty()) {
            return Map.of();
        }
        var reports = new HashMap<Long, Summary>();
        new NamedParameterJdbcTemplate(db).query("""
                SELECT s.followup_id,r.id,r.report_status,r.recovered_at,
                    JSON_UNQUOTE(JSON_EXTRACT(r.recovery_snapshot_json,'$.returnId')) AS recovery_return_id
                FROM fams_loss_return_source s JOIN fams_loss_report r ON r.id=s.report_id
                WHERE s.followup_id IN (:ids) ORDER BY s.followup_id,r.id
                """, Map.of("ids", followupIds), row -> {
                    reports.put(row.getLong("followup_id"), new Summary(row.getString("id"),
                            row.getString("report_status"), row.getTimestamp("recovered_at") != null,
                            row.getString("recovery_return_id")));
                });
        return reports;
    }
}
