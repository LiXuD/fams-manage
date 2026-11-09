-- Let the initialization window carry an opening pending-scrap request without faking an employee application.
ALTER TABLE fams_scrap_request
 ADD COLUMN initial_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER source_item_id,
 ADD COLUMN initial_sheet_row INT NULL AFTER initial_batch_id,
 ADD UNIQUE KEY uk_scrap_request_initial_row (initial_batch_id,initial_sheet_row),
 ADD CONSTRAINT fk_scrap_request_initial_batch FOREIGN KEY (initial_batch_id) REFERENCES fams_asset_import_batch(id),
 ADD CONSTRAINT ck_scrap_request_initial CHECK (
  (initial_batch_id IS NULL AND initial_sheet_row IS NULL)
  OR (initial_batch_id IS NOT NULL AND initial_sheet_row IS NOT NULL AND initial_sheet_row >= 2
   AND source_business_id IS NULL AND source_item_id IS NULL))
;
