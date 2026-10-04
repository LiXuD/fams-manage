ALTER TABLE fams_warehouse
    ADD COLUMN description VARCHAR(500) NULL AFTER name;

CREATE TABLE fams_asset_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    parent_id BIGINT NULL,
    description VARCHAR(500) NULL,
    suggested_unit VARCHAR(32) NULL,
    numbering_prefix VARCHAR(32) NULL,
    extension_template_json JSON NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_asset_category_code (code_normalized),
    KEY idx_fams_asset_category_parent_status (parent_id, status),
    CONSTRAINT fk_fams_asset_category_parent FOREIGN KEY (parent_id)
        REFERENCES fams_asset_category(id),
    CONSTRAINT ck_fams_asset_category_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_asset_category_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_manufacturer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_manufacturer_code (code_normalized),
    CONSTRAINT ck_fams_manufacturer_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_manufacturer_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_brand (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    default_manufacturer_id BIGINT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_brand_code (code_normalized),
    KEY idx_fams_brand_manufacturer (default_manufacturer_id),
    CONSTRAINT fk_fams_brand_manufacturer FOREIGN KEY (default_manufacturer_id)
        REFERENCES fams_manufacturer(id),
    CONSTRAINT ck_fams_brand_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_brand_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_location (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    location_type VARCHAR(20) NOT NULL,
    parent_id BIGINT NULL,
    warehouse_id BIGINT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_location_code (code_normalized),
    KEY idx_fams_location_parent_status (parent_id, status),
    KEY idx_fams_location_warehouse_status (warehouse_id, status),
    CONSTRAINT fk_fams_location_parent FOREIGN KEY (parent_id)
        REFERENCES fams_location(id),
    CONSTRAINT fk_fams_location_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES fams_warehouse(id),
    CONSTRAINT ck_fams_location_type CHECK (
        location_type IN ('BUILDING', 'FLOOR', 'ROOM', 'AREA', 'BIN')),
    CONSTRAINT ck_fams_location_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_fams_location_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
