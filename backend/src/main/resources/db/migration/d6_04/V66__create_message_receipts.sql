-- Per-person read state; shared messages stay one business object while each reader's state is independent.
CREATE TABLE fams_message_receipt (
    account_id BIGINT NOT NULL,
    message_kind VARCHAR(16) NOT NULL,
    message_id BIGINT NOT NULL,
    read_at DATETIME(3) NOT NULL,
    PRIMARY KEY (account_id,message_kind,message_id),
    KEY ix_message_receipt_reader (message_kind,message_id,account_id),
    CONSTRAINT ck_message_receipt_kind CHECK (message_kind IN ('TODO','REMINDER')),
    CONSTRAINT fk_message_receipt_account FOREIGN KEY (account_id) REFERENCES fams_system_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
