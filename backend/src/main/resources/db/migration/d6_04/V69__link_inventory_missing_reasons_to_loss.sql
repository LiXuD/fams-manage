CREATE TABLE fams_loss_inventory_source (
    report_id BIGINT PRIMARY KEY,
    reason_id BIGINT NOT NULL,
    CONSTRAINT fk_loss_inventory_report FOREIGN KEY (report_id) REFERENCES fams_loss_report(id),
    CONSTRAINT fk_loss_inventory_reason FOREIGN KEY (reason_id) REFERENCES fams_inventory_unobserved_reason(id),
    INDEX ix_loss_inventory_attempts (reason_id, report_id)
);
