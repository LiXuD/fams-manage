CREATE TABLE fams_loss_return_source (
    report_id BIGINT PRIMARY KEY,
    followup_id BIGINT NOT NULL,
    CONSTRAINT fk_loss_return_report FOREIGN KEY (report_id) REFERENCES fams_loss_report(id),
    CONSTRAINT fk_loss_return_followup FOREIGN KEY (followup_id) REFERENCES fams_return_followup(id),
    INDEX ix_loss_return_attempts (followup_id, report_id)
);
