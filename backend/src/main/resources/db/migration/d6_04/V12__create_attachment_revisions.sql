CREATE TABLE fams_attachment_revision (
    object_id CHAR(36) NOT NULL,
    previous_object_id CHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    correction_reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (object_id),
    UNIQUE KEY uk_attachment_revision_previous (previous_object_id),
    CONSTRAINT fk_attachment_revision_object FOREIGN KEY (object_id) REFERENCES fams_attachment(object_id),
    CONSTRAINT fk_attachment_revision_previous FOREIGN KEY (previous_object_id) REFERENCES fams_attachment(object_id),
    CONSTRAINT ck_attachment_revision_number CHECK (revision_number >= 2),
    CONSTRAINT ck_attachment_revision_distinct CHECK (object_id <> previous_object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
