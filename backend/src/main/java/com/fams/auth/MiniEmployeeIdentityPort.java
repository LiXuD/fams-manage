package com.fams.auth;

import java.util.Optional;

/** Identity module owns the contract; master data supplies its adapter without creating a module cycle. */
public interface MiniEmployeeIdentityPort {
    Optional<Long> findEmployee(String employeeNo);
    Optional<Long> verify(long employeeId, String suppliedPhone);
    boolean isCurrent(long employeeId, long expectedVersion);
}
