ALTER TABLE fams_scrap_request
 DROP CHECK ck_scrap_request_initial,
 ADD CONSTRAINT ck_scrap_request_initial CHECK (
  (initial_batch_id IS NULL AND initial_sheet_row IS NULL)
  OR (initial_batch_id IS NOT NULL AND initial_sheet_row IS NOT NULL AND initial_sheet_row >= 2
   AND source_type IN ('STANDALONE','REPAIR','LOSS'))
 );
