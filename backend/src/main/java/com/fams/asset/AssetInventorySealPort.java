package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Asset-owned metadata writes participate in the caller's atomic inventory seal. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetInventorySealPort {
    /**
     * One accepted inventory observation handed to the asset domain for date advancement. The
     * owning inventory service has already decided that this observation is accepted; the asset
     * side only validates that all three components are present and positive. {@code observedAt}
     * is converted to a business date in the caller-supplied frozen zone, never in the JVM default.
     *
     * @param assetId fams_asset primary key the observation belongs to
     * @param observationId inventory observation id echoed back on the resulting change
     * @param observedAt instant the observation was taken, zoned to a business date by the seal
     */
    public record AcceptedObservation(long assetId, long observationId, Instant observedAt) {}

    /**
     * Result of advancing one asset's last inventory business date. The record reports what the
     * seal computed, not only what it wrote: when {@code effectiveDate} equals
     * {@code previousDate} the row was already up to date and no UPDATE ran, which the caller needs
     * in order to keep its own change log honest.
     *
     * @param assetId fams_asset primary key that was advanced
     * @param observationId inventory observation id that produced this change
     * @param observedAt instant carried over from the accepted observation
     * @param businessDate proposed business date, {@code observedAt} in the frozen zone
     * @param previousDate stored last_inventory_business_date before the seal, possibly null
     * @param effectiveDate later of the proposed and previous dates, the value actually stored
     */
    public record DateChange(
            long assetId,
            long observationId,
            Instant observedAt,
            LocalDate businessDate,
            LocalDate previousDate,
            LocalDate effectiveDate) {}

    private final JdbcTemplate db;

    /**
     * Wires the seal port to raw JDBC. The class-level {@code MANDATORY} propagation requires the
     * inventory service to have opened the transaction, so the bounded row locks acquired by
     * {@link #lock(List)} are held for the whole seal rather than a nested unit of work.
     *
     * @param db JDBC handle used for the ordered {@code FOR UPDATE} batch and the date updates
     */
    public AssetInventorySealPort(JdbcTemplate db) {
        this.db = db;
    }

    /** Acquire bounded asset locks in the same stable order used by asset review. */
    public boolean lock(List<Long> assets) {
        if (assets.isEmpty()) {
            return true;
        }
        if (assets.size() > 200
                || assets.stream().anyMatch(id -> id == null || id < 1)
                || assets.stream().distinct().count() != assets.size()) {
            throw new IllegalArgumentException("Inventory seal asset batch invalid");
        }
        var sorted = assets.stream().sorted().toList();
        String marks = String.join(",", java.util.Collections.nCopies(sorted.size(), "?"));
        var rows =
                db.queryForList(
                        "SELECT id,card_stage FROM fams_asset WHERE id IN ("
                                + marks
                                + ") ORDER BY id FOR UPDATE",
                        sorted.toArray());
        return rows.size() == sorted.size()
                && rows.stream().allMatch(row -> "ACTIVE".equals(row.get("card_stage")));
    }

    /**
     * Only validated accepted observations are admitted; the owning inventory service decides
     * eligibility.
     */
    public List<DateChange> advance(List<AcceptedObservation> observations, ZoneId frozenZone) {
        java.util.Objects.requireNonNull(frozenZone);
        if (observations.size() > 200
                || observations.stream()
                        .anyMatch(
                                value ->
                                        value == null
                                                || value.assetId() < 1
                                                || value.observationId() < 1
                                                || value.observedAt() == null)
                || observations.stream().map(AcceptedObservation::assetId).distinct().count()
                        != observations.size()) {
            throw new IllegalArgumentException("Inventory accepted-date batch invalid");
        }
        var sorted =
                observations.stream()
                        .sorted(java.util.Comparator.comparingLong(AcceptedObservation::assetId))
                        .toList();
        if (!lock(sorted.stream().map(AcceptedObservation::assetId).toList())) {
            throw new IllegalStateException("Inventory seal asset unavailable");
        }
        var changes = new java.util.ArrayList<DateChange>();
        for (var observation : sorted) {
            var prior =
                    db.queryForObject(
                            "SELECT last_inventory_business_date FROM fams_asset WHERE id=?",
                            (row, ignored) ->
                                    row.getDate(1) == null ? null : row.getDate(1).toLocalDate(),
                            observation.assetId());
            LocalDate proposed = observation.observedAt().atZone(frozenZone).toLocalDate();
            LocalDate effective = prior == null || proposed.isAfter(prior) ? proposed : prior;
            if (!java.util.Objects.equals(prior, effective)) {
                db.update(
                        "UPDATE fams_asset SET last_inventory_business_date=? WHERE id=?",
                        java.sql.Date.valueOf(effective),
                        observation.assetId());
            }
            changes.add(
                    new DateChange(
                            observation.assetId(),
                            observation.observationId(),
                            observation.observedAt(),
                            proposed,
                            prior,
                            effective));
        }
        return List.copyOf(changes);
    }
}
