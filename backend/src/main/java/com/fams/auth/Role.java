package com.fams.auth;

/**
 * Functional roles an account can hold. Domain services check these roles (for example asset
 * admin, warehouse operator or system admin) to gate operations; the effective set is carried on
 * the authenticated actor context.
 */
public enum Role {
    EMPLOYEE,
    DEPARTMENT_ASSET_MANAGER,
    ASSET_ADMIN,
    WAREHOUSE_OPERATOR,
    TECH_ASSISTANT,
    AUDIT_READ_ONLY,
    SYSTEM_ADMIN
}
