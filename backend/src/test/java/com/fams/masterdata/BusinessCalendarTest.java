package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BusinessCalendarTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> WEEK = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");

    private static String snapshot(List<String> holidays, List<String> adjusted) {
        return "{\"timezone\":\"Asia/Shanghai\",\"workWeek\":" + JSON.writeValueAsString(WEEK)
                + ",\"holidays\":" + JSON.writeValueAsString(holidays)
                + ",\"adjustedWorkdays\":" + JSON.writeValueAsString(adjusted) + "}";
    }

    @Test
    void weekendSkipsToTheNextWorkdayAndExpiresAtItsEnd() {
        var calendar = JSON.readTree(snapshot(List.of(), List.of()));
        assertEquals(DayOfWeek.FRIDAY, LocalDate.of(2026, 11, 6).getDayOfWeek());
        assertEquals(Instant.parse("2026-11-09T16:00:00Z"), BusinessCalendar.workdayDeadline(
                calendar, SHANGHAI, Instant.parse("2026-11-06T02:00:00Z"), 1));
        assertEquals(Instant.parse("2026-11-10T16:00:00Z"), BusinessCalendar.workdayDeadline(
                calendar, SHANGHAI, Instant.parse("2026-11-06T02:00:00Z"), 2));
    }

    @Test
    void holidaysAreSkippedAndAdjustedWeekendsOverrideThem() {
        var holiday = JSON.readTree(snapshot(List.of("2026-11-09"), List.of()));
        assertEquals(Instant.parse("2026-11-10T16:00:00Z"), BusinessCalendar.workdayDeadline(
                holiday, SHANGHAI, Instant.parse("2026-11-06T02:00:00Z"), 1));
        var adjusted = JSON.readTree(snapshot(List.of("2026-11-07"), List.of("2026-11-07")));
        assertEquals(Instant.parse("2026-11-07T16:00:00Z"), BusinessCalendar.workdayDeadline(
                adjusted, SHANGHAI, Instant.parse("2026-11-06T02:00:00Z"), 1));
        assertTrue(BusinessCalendar.isWorkday(adjusted, LocalDate.of(2026, 11, 7)));
    }

    @Test
    void yearBoundaryCountsAcrossTheNewYear() {
        var calendar = JSON.readTree(snapshot(List.of("2027-01-01"), List.of()));
        assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2027, 1, 2).getDayOfWeek());
        assertEquals(Instant.parse("2027-01-04T16:00:00Z"), BusinessCalendar.workdayDeadline(
                calendar, SHANGHAI, Instant.parse("2026-12-31T02:00:00Z"), 1));
    }

    @Test
    void deadlineStartsFromTheEnterpriseZoneDayNotTheUtcInstant() {
        var calendar = JSON.readTree(snapshot(List.of(), List.of()));
        var moment = Instant.parse("2026-11-06T17:30:00Z");
        assertEquals(Instant.parse("2026-11-09T16:00:00Z"),
                BusinessCalendar.workdayDeadline(calendar, SHANGHAI, moment, 1));
        assertEquals(Instant.parse("2026-11-10T00:00:00Z"),
                BusinessCalendar.workdayDeadline(calendar, ZoneId.of("UTC"), moment, 1));
    }

    @Test
    void workdayRecognitionUsesTheFrozenWeekAndHolidaySets() {
        var calendar = JSON.readTree(snapshot(List.of("2026-11-09"), List.of("2026-11-14")));
        assertTrue(BusinessCalendar.isWorkday(calendar, LocalDate.of(2026, 11, 6)));
        assertFalse(BusinessCalendar.isWorkday(calendar, LocalDate.of(2026, 11, 7)));
        assertFalse(BusinessCalendar.isWorkday(calendar, LocalDate.of(2026, 11, 9)));
        assertTrue(BusinessCalendar.isWorkday(calendar, LocalDate.of(2026, 11, 14)));
    }

    @Test
    void aDeadlineRequiresAtLeastOneWorkday() {
        var calendar = JSON.readTree(snapshot(List.of(), List.of()));
        var failure = assertThrows(IllegalArgumentException.class, () -> BusinessCalendar.workdayDeadline(
                calendar, SHANGHAI, Instant.parse("2026-11-06T02:00:00Z"), 0));
        assertEquals("ASSET_PARAMETER_WORKDAYS_INVALID", failure.getMessage());
    }
}
