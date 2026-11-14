-- Expand the accepted terminal states without rewriting any loan rows.
ALTER TABLE fams_loan
    DROP CHECK ck_loan_status,
    DROP CHECK ck_loan_closed,
    ADD CONSTRAINT ck_loan_relation_status CHECK (relation_status IN ('OPEN','RETURNED','VOID','LOST_TERMINATED')),
    ADD CONSTRAINT ck_loan_relation_closed CHECK (
        (relation_status='OPEN' AND closed_at IS NULL)
        OR (relation_status IN ('RETURNED','VOID','LOST_TERMINATED') AND closed_at IS NOT NULL));
