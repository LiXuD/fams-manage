package com.fams.auth;

import java.util.Optional;

/** Identity module owns the contract; master data supplies its adapter without creating a module cycle. */
public interface MiniEmployeeIdentityPort {
    /**
     * Resolves an employee by employee number during binding preparation.
     *
     * @param employeeNo employee number supplied by the binding request
     * @return the matching employee id, or empty when no such employee exists
     */
    Optional<Long> findEmployee(String employeeNo);
    /**
     * Checks the supplied phone against the employee's registered phone during binding
     * preparation.
     *
     * @param employeeId employee being bound
     * @param suppliedPhone phone number supplied by the binding request
     * @return the employee's current record version on match, or empty on mismatch
     */
    Optional<Long> verify(long employeeId, String suppliedPhone);
    /**
     * Freshness check performed at binding confirmation so a stale challenge cannot complete
     * after the employee record changed.
     *
     * @param employeeId employee being bound
     * @param expectedVersion employee version captured when the challenge was prepared
     * @return true when the employee record is still at the expected version
     */
    boolean isCurrent(long employeeId, long expectedVersion);
}
