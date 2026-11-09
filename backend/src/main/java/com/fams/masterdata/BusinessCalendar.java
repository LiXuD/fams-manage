package com.fams.masterdata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import tools.jackson.databind.JsonNode;

/** Enterprise calendar shared by every deadline and reminder; an object keeps using the snapshot it froze at creation. */
public final class BusinessCalendar {
    private BusinessCalendar() { }

    public static boolean isWorkday(JsonNode snapshot, LocalDate date) {
        if (containsDate(snapshot.path("adjustedWorkdays"), date)) {
            return true;
        }
        return containsDay(snapshot.path("workWeek"), date)
                && !containsDate(snapshot.path("holidays"), date);
    }

    /**
     * Start of the day following the nth workday after the given moment, in the supplied zone, so a
     * deadline covers exactly that many whole workdays.
     */
    public static Instant workdayDeadline(JsonNode snapshot, ZoneId zone, Instant moment, long workdays) {
        if (workdays < 1) {
            throw new IllegalArgumentException("ASSET_PARAMETER_WORKDAYS_INVALID");
        }
        LocalDate date = LocalDate.ofInstant(moment, zone);
        for (long counted = 0; counted < workdays; ) {
            date = date.plusDays(1);
            if (isWorkday(snapshot, date)) {
                counted++;
            }
        }
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static boolean containsDate(JsonNode values, LocalDate date) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (date.toString().equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDay(JsonNode values, LocalDate date) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (date.getDayOfWeek().name().equals(value.asText())) {
                return true;
            }
        }
        return false;
    }
}
