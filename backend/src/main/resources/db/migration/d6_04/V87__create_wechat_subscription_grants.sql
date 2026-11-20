CREATE TABLE fams_wechat_subscription_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    template_id VARCHAR(80) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    business_type VARCHAR(40) NOT NULL,
    business_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    consumed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fams_wechat_grant_request (account_id, request_key),
    KEY idx_fams_wechat_grant_available (account_id, status, template_id),
    CONSTRAINT fk_fams_wechat_grant_account FOREIGN KEY (account_id)
        REFERENCES fams_system_account(id),
    CONSTRAINT ck_fams_wechat_grant_scene CHECK (scene IN ('PROGRESS', 'RETURN', 'INVENTORY_TASK', 'RECEIPT')),
    CONSTRAINT ck_fams_wechat_grant_business CHECK (business_type IN (
        'REQUISITION', 'LOAN', 'REPAIR', 'INVENTORY_TASK',
        'REQUISITION_ALLOCATION', 'TRANSFER_ITEM', 'INBOUND')),
    CONSTRAINT ck_fams_wechat_grant_status CHECK (status IN ('AVAILABLE', 'CONSUMED')),
    CONSTRAINT ck_fams_wechat_grant_template CHECK (template_id REGEXP '^[A-Za-z0-9_-]{1,80}$'),
    CONSTRAINT ck_fams_wechat_grant_consumed CHECK (
        (status = 'AVAILABLE' AND consumed_at IS NULL AND delivery_key IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE fams_notification_delivery DROP CHECK ck_fams_notification_delivery_channel;
ALTER TABLE fams_notification_delivery
    ADD CONSTRAINT ck_fams_notification_delivery_channel CHECK (channel IN ('SMS', 'WECHAT'));
ALTER TABLE fams_notification_delivery ADD COLUMN grant_id BIGINT NULL;
ALTER TABLE fams_notification_delivery
    ADD CONSTRAINT fk_fams_notification_delivery_grant FOREIGN KEY (grant_id)
        REFERENCES fams_wechat_subscription_grant(id);
