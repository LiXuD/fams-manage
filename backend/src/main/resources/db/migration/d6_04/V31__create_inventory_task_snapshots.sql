-- Asset-owned public v1 projection: no contacts, secrets or private mutation surface.
CREATE VIEW fams_asset_inventory_source_v1 AS
SELECT a.id AS asset_id,a.version AS asset_version,a.using_organization_id,
       a.owning_organization_id,a.category_id,a.storage_warehouse_id,a.current_location_id,
       a.lifecycle_status,a.exception_status,
       COALESCE(a.responsible_employee_id,a.department_responsible_employee_id) AS responsible_employee_id,
       JSON_OBJECT('assetId',CAST(a.id AS CHAR),'assetCode',a.asset_code,'name',a.name,
         'version',a.version,'categoryId',CAST(a.category_id AS CHAR),'categoryName',c.name,
         'owningOrganizationId',CAST(a.owning_organization_id AS CHAR),'owningOrganizationName',o.name,
         'lifecycle',a.lifecycle_status,'exception',a.exception_status,'custodyType',a.custody_type,
         'warehouseId',CAST(a.storage_warehouse_id AS CHAR),'warehouseName',w.name,
         'locationId',CAST(a.current_location_id AS CHAR),'locationName',l.name,
         'usingOrganizationId',CAST(a.using_organization_id AS CHAR),'usingOrganizationName',u.name,
         'responsibleEmployeeId',CAST(a.responsible_employee_id AS CHAR),'responsibleEmployeeName',e.name,
         'departmentResponsibleEmployeeId',CAST(a.department_responsible_employee_id AS CHAR),
         'departmentResponsibleEmployeeName',d.name,'parameterVersionId',CAST(a.parameter_version_id AS CHAR)) AS snapshot_json
FROM fams_asset a
LEFT JOIN fams_asset_category c ON c.id=a.category_id
LEFT JOIN fams_organization o ON o.id=a.owning_organization_id
LEFT JOIN fams_organization u ON u.id=a.using_organization_id
LEFT JOIN fams_warehouse w ON w.id=a.storage_warehouse_id
LEFT JOIN fams_location l ON l.id=a.current_location_id
LEFT JOIN fams_employee e ON e.id=a.responsible_employee_id
LEFT JOIN fams_employee d ON d.id=a.department_responsible_employee_id
WHERE a.card_stage='ACTIVE' AND a.lifecycle_status<>'WAITING_INBOUND';

CREATE TABLE fams_inventory_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(128) NOT NULL,
 task_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 range_json JSON NOT NULL,
 starts_at DATETIME(3) NOT NULL,
 due_at DATETIME(3) NOT NULL,
 timezone_id VARCHAR(64) NULL,
 parameter_version_id BIGINT NULL,
 expected_count BIGINT NOT NULL DEFAULT 0,
 -- Task-wide count includes manual task-outside findings; collection belongs to D6-17.
 captured_result_count BIGINT NOT NULL DEFAULT 0,
 assignment_version BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 creator_account_id BIGINT NOT NULL,
 publisher_account_id BIGINT NULL,
 copied_from_task_id BIGINT NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 published_at DATETIME(3) NULL,
 started_at DATETIME(3) NULL,
 ended_at DATETIME(3) NULL,
 end_reason VARCHAR(1000) NULL,
 CONSTRAINT ck_inventory_task_status CHECK(task_status IN
   ('DRAFT','READY','IN_PROGRESS','WAITING_REVIEW','COMPLETED','CANCELLED','TERMINATED','DELETED')),
 CONSTRAINT ck_inventory_task_time CHECK(due_at>starts_at),
 CONSTRAINT ck_inventory_task_versions CHECK(version>=0 AND assignment_version>=0 AND expected_count>=0 AND captured_result_count>=0),
 CONSTRAINT fk_inventory_creator FOREIGN KEY(creator_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_publisher FOREIGN KEY(publisher_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_parameter FOREIGN KEY(parameter_version_id) REFERENCES fams_business_parameter_version(id),
 CONSTRAINT fk_inventory_copy FOREIGN KEY(copied_from_task_id) REFERENCES fams_inventory_task(id),
 INDEX ix_inventory_due(task_status,starts_at,id)
);
CREATE TABLE fams_inventory_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 asset_version BIGINT NOT NULL,
 snapshot_json JSON NOT NULL,
 executor_account_id BIGINT NULL,
 assignment_version BIGINT NOT NULL DEFAULT 0,
 result_count BIGINT NOT NULL DEFAULT 0,
 recheck_generation BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_inventory_item UNIQUE(task_id,asset_id),
 CONSTRAINT fk_inventory_item_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_item_asset FOREIGN KEY(asset_id) REFERENCES fams_asset(id),
 CONSTRAINT fk_inventory_item_executor FOREIGN KEY(executor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_inventory_item_versions CHECK(asset_version>=0 AND assignment_version>=0 AND result_count>=0 AND recheck_generation>=0),
 INDEX ix_inventory_executor(executor_account_id,task_id,id)
);
CREATE TABLE fams_inventory_assignment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,
 old_executor_account_id BIGINT NULL,
 new_executor_account_id BIGINT NOT NULL,
 assignment_version BIGINT NOT NULL,
 recheck_generation BIGINT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor_account_id BIGINT NOT NULL,
 assigned_at DATETIME(3) NOT NULL,
 CONSTRAINT uk_inventory_assignment UNIQUE(item_id,assignment_version),
 CONSTRAINT fk_inventory_assignment_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_assignment_item FOREIGN KEY(item_id) REFERENCES fams_inventory_item(id),
 CONSTRAINT fk_inventory_assignment_old FOREIGN KEY(old_executor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_assignment_new FOREIGN KEY(new_executor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_inventory_assignment_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id)
);
CREATE TABLE fams_inventory_participant (
 task_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 first_assigned_at DATETIME(3) NOT NULL,
 revoked_at DATETIME(3) NULL,
 PRIMARY KEY(task_id,account_id),
 CONSTRAINT fk_inventory_participant_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_participant_account FOREIGN KEY(account_id) REFERENCES fams_system_account(id)
);
CREATE TABLE fams_inventory_task_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL,
 detail_json JSON NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_inventory_event_task FOREIGN KEY(task_id) REFERENCES fams_inventory_task(id),
 CONSTRAINT fk_inventory_event_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 INDEX ix_inventory_events(task_id,id)
);
