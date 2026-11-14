package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loss owns access to and interpretation of confirmed loss evidence. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossScrapSourcePort {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public LossScrapSourcePort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record FactorySource(long repairId, long roundId, long processorAccountId) {}

    public record ConfirmedLoss(long assetId, String lifecycle, String sourceJson,
            Instant confirmedAt, String confirmationJson, FactorySource factory) {}

    public ConfirmedLoss requireSubmissionEvidence(Current current, long id, long version) {
        var row = lockedReport(id);
        if (!canSubmit(current, row)) {
            throw new LossFailure(403, "SCRAP_LOSS_SOURCE_FORBIDDEN");
        }
        return confirmedSource(row, version);
    }

    static boolean canSubmit(Current current, Map<String, Object> row) {
        return current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR) && Objects.equals(current.id(), row.get("processor_account_id"))
                || current.has(Role.EMPLOYEE)
                        && (Objects.equals(current.employeeId(), row.get("responsible_employee_id"))
                                || Objects.equals(current.employeeId(), row.get("shared_responsible_employee_id")))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && Objects.equals(current.organizationId(), row.get("using_organization_id"));
    }

    static boolean confirmedScrapSource(Map<String, Object> row, JsonNode source) {
        return "CONFIRMED_LOST".equals(row.get("report_status")) && row.get("recovered_at") == null
                && (List.of("BORROWED", "IN_USE", "IN_STOCK").contains(source.path("lifecycle").asText())
                        || factorySource(row, source) != null)
                && row.get("confirmation_snapshot_json") != null && row.get("confirmed_lost_at") != null;
    }

    private static FactorySource factorySource(Map<String, Object> row, JsonNode source) {
        if (!"FACTORY_REPAIR".equals(source.path("lifecycle").asText())) {
            return null;
        }
        var factory = source.path("factorySource");
        long repair = factory.path("repairId").asLong(), round = factory.path("roundId").asLong();
        long processor = factory.path("processorAccountId").asLong();
        return repair > 0 && round > 0 && processor > 0 && Objects.equals(row.get("processor_account_id"), processor)
                ? new FactorySource(repair, round, processor) : null;
    }

    /** The scrap workflow has already authorized its processor independently of the old loss processor. */
    public ConfirmedLoss requireExecutionEvidence(long reportId, long version) {
        return confirmedSource(lockedReport(reportId), version);
    }

    public void closeFollowUp(long reportId, Instant time) {
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='LOST_REPORT'"
                + " AND business_id=? AND todo_type='LOST_FOLLOW_UP' AND closed_at IS NULL", time, reportId);
    }

    private Map<String, Object> lockedReport(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_report WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private ConfirmedLoss confirmedSource(Map<String, Object> row, long version) {
        var source = json.readTree(row.get("source_snapshot_json").toString());
        if (((Number) row.get("version")).longValue() != version || !confirmedScrapSource(row, source)) {
            throw new LossFailure(409, "SCRAP_LOSS_SOURCE_CHANGED");
        }
        Object confirmed = row.get("confirmed_lost_at");
        Instant at = confirmed instanceof java.sql.Timestamp timestamp ? timestamp.toInstant()
                : ((java.time.LocalDateTime) confirmed).toInstant(ZoneOffset.UTC);
        return new ConfirmedLoss(((Number) row.get("asset_id")).longValue(), source.path("lifecycle").asText(),
                source.toString(), at, row.get("confirmation_snapshot_json").toString(), factorySource(row, source));
    }
}
