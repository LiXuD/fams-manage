CREATE TABLE fams_organization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    parent_id BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    remark VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_organization_code (code_normalized),
    KEY idx_fams_organization_parent_status (parent_id, status),
    CONSTRAINT fk_fams_organization_parent FOREIGN KEY (parent_id)
        REFERENCES fams_organization(id),
    CONSTRAINT ck_fams_organization_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_organization_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_employee (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_no VARCHAR(64) NOT NULL,
    employee_no_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    organization_id BIGINT NOT NULL,
    mobile_ciphertext VARBINARY(1024) NULL,
    mobile_lookup_hash BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_employee_no (employee_no_normalized),
    UNIQUE KEY uk_fams_employee_mobile_lookup (mobile_lookup_hash),
    KEY idx_fams_employee_org_status (organization_id, status),
    CONSTRAINT fk_fams_employee_organization FOREIGN KEY (organization_id)
        REFERENCES fams_organization(id),
    CONSTRAINT ck_fams_employee_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_employee_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_system_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    username VARCHAR(128) NOT NULL,
    username_normalized VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    initial_password_required BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME(3) NULL,
    auth_epoch BIGINT NOT NULL DEFAULT 0,
    authorization_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_account_employee (employee_id),
    UNIQUE KEY uk_fams_account_username (username_normalized),
    CONSTRAINT fk_fams_account_employee FOREIGN KEY (employee_id)
        REFERENCES fams_employee(id),
    CONSTRAINT ck_fams_account_status CHECK (status IN ('ENABLED', 'DISABLED', 'LOCKED')),
    CONSTRAINT ck_fams_account_failed_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_fams_account_auth_epoch CHECK (auth_epoch >= 0),
    CONSTRAINT ck_fams_account_authorization_version CHECK (authorization_version >= 0),
    CONSTRAINT ck_fams_account_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_role (
    account_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    granted_at DATETIME(3) NOT NULL,
    granted_by BIGINT NULL,
    PRIMARY KEY (account_id, role_code),
    CONSTRAINT fk_fams_account_role_account FOREIGN KEY (account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_fams_account_role_code CHECK (role_code IN (
        'EMPLOYEE', 'DEPARTMENT_ASSET_MANAGER', 'ASSET_ADMIN', 'WAREHOUSE_OPERATOR',
        'TECH_ASSISTANT', 'AUDIT_READ_ONLY', 'SYSTEM_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_authorization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    generation_no BIGINT NOT NULL,
    authorization_status VARCHAR(20) NOT NULL,
    predecessor_authorization_id BIGINT NULL,
    superseded_at DATETIME(3) NULL,
    changed_by BIGINT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN superseded_at IS NULL THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_authorization_generation (account_id, role_code, generation_no),
    UNIQUE KEY uk_fams_authorization_current (account_id, role_code, current_guard),
    CONSTRAINT fk_fams_authorization_account FOREIGN KEY (account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_fams_authorization_status CHECK (authorization_status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_data_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    authorization_id BIGINT NOT NULL,
    scope_type VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_scope_type (authorization_id, scope_type),
    CONSTRAINT fk_fams_scope_authorization FOREIGN KEY (authorization_id)
        REFERENCES fams_account_authorization(id) ON DELETE CASCADE,
    CONSTRAINT ck_fams_scope_type CHECK (scope_type IN (
        'SELF', 'OWN_ORGANIZATION', 'OWN_ORGANIZATION_AND_DESCENDANTS',
        'SPECIFIED_ORGANIZATIONS', 'SPECIFIED_WAREHOUSES', 'ENTERPRISE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_scope_organization (
    data_scope_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    organization_version_at_grant BIGINT NOT NULL,
    PRIMARY KEY (data_scope_id, organization_id),
    CONSTRAINT fk_fams_scope_org_scope FOREIGN KEY (data_scope_id)
        REFERENCES fams_account_data_scope(id) ON DELETE CASCADE,
    CONSTRAINT fk_fams_scope_org_organization FOREIGN KEY (organization_id)
        REFERENCES fams_organization(id),
    CONSTRAINT ck_fams_scope_org_version CHECK (organization_version_at_grant >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_scope_warehouse (
    data_scope_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    warehouse_version_at_grant BIGINT NOT NULL,
    PRIMARY KEY (data_scope_id, warehouse_id),
    CONSTRAINT fk_fams_scope_warehouse_scope FOREIGN KEY (data_scope_id)
        REFERENCES fams_account_data_scope(id) ON DELETE CASCADE,
    CONSTRAINT ck_fams_scope_warehouse_version CHECK (warehouse_version_at_grant >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_account_field_permission (
    authorization_id BIGINT NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    granted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (authorization_id, permission_code),
    CONSTRAINT fk_fams_field_permission_authorization FOREIGN KEY (authorization_id)
        REFERENCES fams_account_authorization(id) ON DELETE CASCADE,
    CONSTRAINT ck_fams_field_permission_code CHECK (permission_code IN (
        'EMPLOYEE_PHONE', 'ASSET_SERVICE_PHONE', 'BUSINESS_ATTACHMENT', 'REPAIR_COST',
        'BULK_EXPORT', 'SCRAP_ATTACHMENT', 'SYSTEM_OPERATION_LOG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_account_id BIGINT NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    http_status INT NOT NULL,
    response_body_json LONGTEXT NOT NULL,
    resource_type VARCHAR(80) NULL,
    resource_id BIGINT NULL,
    resource_version BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_idempotency_actor_operation_key (
        actor_account_id, operation_id, idempotency_key),
    CONSTRAINT fk_fams_idempotency_actor FOREIGN KEY (actor_account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_fams_idempotency_status CHECK (result_status IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT ck_fams_idempotency_resource_version CHECK (
        resource_version IS NULL OR resource_version >= 0),
    CONSTRAINT ck_fams_idempotency_response_json CHECK (JSON_VALID(response_body_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_security_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NULL,
    event_type VARCHAR(80) NOT NULL,
    request_id CHAR(36) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    detail_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_fams_security_account_time (account_id, occurred_at),
    KEY idx_fams_security_request (request_id),
    CONSTRAINT fk_fams_security_account FOREIGN KEY (account_id)
        REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
