-- Additive: legacy writers may still create rows without the new snapshot.
ALTER TABLE fams_repair_request
    ADD COLUMN first_execution_parameter_snapshot_json JSON NULL;
