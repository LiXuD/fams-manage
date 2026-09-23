package com.fams.masterdata;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.fams.product.contract.model.NumberingTarget;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("fams-product")
@Service
class NumberAllocationService {

    private final NumberingRuleMapper rules;
    private final NumberAllocationMapper allocations;
    private final BusinessParameterService parameters;
    private final Clock clock;

    NumberAllocationService(NumberingRuleMapper rules, NumberAllocationMapper allocations,
            BusinessParameterService parameters, Clock authClock) {
        this.rules = rules;
        this.allocations = allocations;
        this.parameters = parameters;
        this.clock = authClock;
    }

    @Transactional
    List<NumberReservation> reserve(NumberingTarget target, Long categoryId, int count) {
        if (target == null || count < 1 || count > 1000) {
            throw MasterDataOperationException.invalid("The numbering request is invalid.");
        }
        NumberingRuleRow active = rules.findActiveTarget(target.name());
        if (active == null) {
            throw new MasterDataOperationException("NUMBERING_RULE_UNAVAILABLE",
                    "No active numbering rule owns this target.", 409);
        }
        NumberingRuleRow rule = rules.findForUpdate(active.id());
        if (rule == null || !"ACTIVE".equals(rule.status()) || !target.name().equals(rule.target())) {
            throw new MasterDataOperationException("NUMBERING_RULE_UNAVAILABLE",
                    "The numbering rule changed.", 409);
        }
        String prefix = rule.fixedPrefix();
        if ("CATEGORY_PREFIX".equals(rule.mode())) {
            if (categoryId == null) {
                throw prefixMissing();
            }
            prefix = allocations.activeCategoryPrefix(categoryId);
            if (prefix == null || !prefix.matches("[A-Za-z0-9_-]{1,32}")) {
                throw prefixMissing();
            }
        }
        LocalDate today = LocalDate.now(clock.withZone(parameters.currentZoneOrDefault()));
        String date = datePart(rule.datePart(), today);
        String namespace = target.name() + ":" + rule.mode() + ":"
                + (prefix == null ? "" : prefix.toUpperCase(java.util.Locale.ROOT))
                + ":" + rule.datePart() + ":" + date;
        String displayPrefix = target.name() + "-" + ("CATEGORY_PREFIX".equals(rule.mode())
                ? "C" : "E") + "-" + (prefix == null || prefix.isEmpty()
                ? "N" : "P" + prefix.toUpperCase(java.util.Locale.ROOT)) + "-"
                + (date.isEmpty() ? "N" : "D" + rule.datePart() + date);
        var now = clock.instant();
        allocations.ensureSequence(namespace, now);
        Long previous = allocations.lockSequence(namespace);
        if (previous == null) {
            throw MasterDataOperationException.writeConflict("The number sequence is unavailable.");
        }
        long maximum = Long.parseLong("9".repeat(rule.sequenceLength()));
        if (previous > maximum - count) {
            throw new MasterDataOperationException("NUMBERING_SEQUENCE_EXHAUSTED",
                    "The number sequence is exhausted.", 409);
        }
        if (allocations.advanceSequence(namespace, previous, previous + count, now) != 1) {
            throw MasterDataOperationException.writeConflict("The number sequence changed.");
        }
        List<NumberReservation> result = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            long sequence = previous + index;
            String rendered = displayPrefix + "-"
                    + String.format(java.util.Locale.ROOT, "%0" + rule.sequenceLength() + "d", sequence);
            NewNumberAllocationRow row = new NewNumberAllocationRow(namespace, sequence, rendered, now);
            if (allocations.insert(row) != 1) {
                throw MasterDataOperationException.writeConflict("The number reservation failed.");
            }
            result.add(new NumberReservation(row.getId(), rendered));
        }
        return List.copyOf(result);
    }

    @Transactional
    void markUsed(long reservationId) {
        finish(reservationId, "USED");
    }

    @Transactional
    void voidReservation(long reservationId) {
        finish(reservationId, "VOID");
    }

    private void finish(long reservationId, String state) {
        if (allocations.finish(reservationId, state) != 1) {
            throw MasterDataOperationException.writeConflict("The number reservation is no longer open.");
        }
    }

    private String datePart(String part, LocalDate date) {
        return switch (part) {
            case "NONE" -> "";
            case "YYYY" -> date.format(DateTimeFormatter.ofPattern("uuuu"));
            case "YYYYMM" -> date.format(DateTimeFormatter.ofPattern("uuuuMM"));
            case "YYYYMMDD" -> date.format(DateTimeFormatter.BASIC_ISO_DATE);
            default -> throw MasterDataOperationException.invalid("The numbering date part is invalid.");
        };
    }

    private MasterDataOperationException prefixMissing() {
        return new MasterDataOperationException("NUMBERING_CATEGORY_PREFIX_MISSING",
                "An active category with a legal prefix is required.", 409);
    }
}

record NumberReservation(long id, String number) {
}
