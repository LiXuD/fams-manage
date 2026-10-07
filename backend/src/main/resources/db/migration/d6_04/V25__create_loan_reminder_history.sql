CREATE TABLE fams_loan_reminder (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    loan_id BIGINT NOT NULL,
    expected_return_date DATE NOT NULL,
    reminder_business_date DATE NOT NULL,
    reminder_type VARCHAR(24) NOT NULL,
    overdue_from DATE NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_loan_reminder_day (loan_id,expected_return_date,reminder_business_date,reminder_type),
    KEY idx_loan_reminder_history (loan_id,id),
    CONSTRAINT fk_loan_reminder_loan FOREIGN KEY (loan_id) REFERENCES fams_loan(id),
    CONSTRAINT ck_loan_reminder_type CHECK (reminder_type IN ('DUE_IN_3_DAYS','DUE_IN_1_DAY','DUE_TODAY','OVERDUE','LOST_LIABILITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
