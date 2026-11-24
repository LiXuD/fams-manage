package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(LossScrapSourcePort.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;

    /**
     * Wires the port to the loss tables and the mapper used to interpret stored snapshots.
     *
     * @param db JDBC template reading {@code fams_loss_report} and closing follow-up todos
     * @param json mapper parsing the report's source snapshot JSON
     */
    public LossScrapSourcePort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Factory origin of a confirmed loss: the repair and round ids plus the processor account
     * recorded in the frozen source snapshot, present only when the report still points at that
     * round.
     */
    public record FactorySource(long repairId, long roundId, long processorAccountId) {}

    /**
     * Evidence bundle handed to the scrap workflow: the lost asset with its frozen lifecycle and
     * source snapshot, the confirmation timestamp and snapshot, and the factory origin when the
     * loss was reported from a factory repair round.
     */
    public record ConfirmedLoss(long assetId, String lifecycle, String sourceJson,
            Instant confirmedAt, String confirmationJson, FactorySource factory) {}

    /**
     * Locks the loss report and returns its confirmed-loss evidence for a scrap submission. The
     * caller must be authorized for the report (asset admin, the assigned warehouse processor, a
     * responsible employee or the using organization's department asset manager) and the report
     * must still be an unrecovered confirmed loss whose version matches the caller's view.
     *
     * @param current revalidated actor requesting the scrap submission evidence
     * @param id loss report id
     * @param version expected report version (If-Match) held by the scrap caller
     * @return the confirmed-loss evidence bundle for the scrap workflow
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} if the report does not exist,
     *         {@code SCRAP_LOSS_SOURCE_FORBIDDEN} when the caller lacks access to the report, or
     *         {@code SCRAP_LOSS_SOURCE_CHANGED} when the version drifted or the report no longer
     *         qualifies as a confirmed scrap source
     */
    public ConfirmedLoss requireSubmissionEvidence(Current current, long id, long version) {
        LOG.debug("Locked fams_loss_report row {} for scrap submission evidence.", id);
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

    /**
     * Returns the confirmed-loss evidence for a scrap execution, locking the loss report row first.
     *
     * <p>The scrap workflow has already authorized its processor independently of the old loss
     * processor, so this port only re-reads the locked report and validates its version.
     *
     * @param reportId identifier of the confirmed loss report to lock and read
     * @param version  ownership version the caller observed; a mismatch is rejected by the caller
     * @return the confirmed loss evidence projected from the locked report row
     */
    public ConfirmedLoss requireExecutionEvidence(long reportId, long version) {
        return confirmedSource(lockedReport(reportId), version);
    }

    /**
     * Closes the open follow-up todo of a loss report when the scrap workflow takes over, so the
     * periodic loss follow-up queue no longer lists an asset that is being scrapped.
     *
     * @param reportId loss report id whose follow-up todo is closed
     * @param time timestamp recorded as the todo's close time
     */
    public void closeFollowUp(long reportId, Instant time) {
        LOG.debug("UPDATE fams_todo closed follow-up of loss report {}.", reportId);
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
