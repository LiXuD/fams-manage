CREATE TABLE fams_attachment_upload_key (
    uploader_account_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_id CHAR(36) NOT NULL,
    expected_business_version BIGINT NOT NULL,
    PRIMARY KEY (uploader_account_id, idempotency_key),
    CONSTRAINT fk_attachment_key_account FOREIGN KEY (uploader_account_id) REFERENCES fams_system_account(id),
    CONSTRAINT fk_attachment_key_object FOREIGN KEY (object_id) REFERENCES fams_attachment(object_id),
    CONSTRAINT ck_attachment_key_version CHECK (expected_business_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
