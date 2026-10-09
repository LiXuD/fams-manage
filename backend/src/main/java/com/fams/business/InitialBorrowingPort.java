package com.fams.business;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Opening responsibility participates in the caller's publication or rollback transaction. */
public interface InitialBorrowingPort {
    void open(long asset, long employee, long organization, UUID batch, int row,
            LocalDate borrowed, LocalDate due, LocalDate businessDate, long parameter, Instant time);
    void assertReversible(long asset, UUID batch);
    void cancel(long asset, UUID batch, Instant time);
}
