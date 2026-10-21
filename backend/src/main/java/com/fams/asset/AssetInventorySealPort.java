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
    public record AcceptedObservation(long assetId, long observationId, Instant observedAt) {}

    public record DateChange(
            long assetId,
            long observationId,
            Instant observedAt,
            LocalDate businessDate,
            LocalDate previousDate,
            LocalDate effectiveDate) {}

    private final JdbcTemplate db;

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
