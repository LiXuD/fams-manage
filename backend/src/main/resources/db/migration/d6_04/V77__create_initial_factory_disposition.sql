CREATE TABLE fams_initial_factory_disposition (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 round_id BIGINT NOT NULL UNIQUE,
 method VARCHAR(24) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 CONSTRAINT fk_initial_factory_disposition_round FOREIGN KEY(round_id) REFERENCES fams_repair_factory_round(id),
 CONSTRAINT ck_initial_factory_disposition_method CHECK(method IN ('FACTORY_RETAINED','FACTORY_DESTROYED')),
 CONSTRAINT ck_initial_factory_disposition_evidence CHECK(CHAR_LENGTH(TRIM(evidence))>0)
);

ALTER TABLE fams_scrap_request
 DROP CHECK ck_scrap_request_initial,
 ADD CONSTRAINT ck_scrap_request_initial CHECK (
  (initial_batch_id IS NULL AND initial_sheet_row IS NULL)
  OR (initial_batch_id IS NOT NULL AND initial_sheet_row IS NOT NULL AND initial_sheet_row >= 2
   AND source_type IN ('STANDALONE','REPAIR','LOSS','FACTORY_REPLACEMENT'))
 );
