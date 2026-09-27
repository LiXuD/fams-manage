CREATE TABLE fams_attachment_scope (
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    PRIMARY KEY (business_type, business_id),
    CONSTRAINT ck_attachment_scope_id CHECK (business_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fams_attachment (
    object_id CHAR(36) NOT NULL PRIMARY KEY,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_format VARCHAR(10) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    scan_status VARCHAR(20) NOT NULL,
    uploader_account_id BIGINT NOT NULL,
    business_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    KEY idx_attachment_business (business_type, business_id, deleted_at),
    CONSTRAINT fk_attachment_scope FOREIGN KEY (business_type, business_id)
        REFERENCES fams_attachment_scope (business_type, business_id),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT ck_attachment_format CHECK (file_format IN ('JPG','PNG','WEBP','PDF')),
    CONSTRAINT ck_attachment_scan CHECK (scan_status IN ('CLEAN','INFECTED','UNAVAILABLE')),
    CONSTRAINT ck_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    CONSTRAINT ck_attachment_image_size CHECK (file_format = 'PDF' OR size_bytes <= 10485760),
    CONSTRAINT ck_attachment_business_version CHECK (business_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
