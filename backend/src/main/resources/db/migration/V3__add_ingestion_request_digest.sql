ALTER TABLE deployment
    ADD COLUMN ingestion_digest CHAR(64);

ALTER TABLE backup_run
    ADD COLUMN ingestion_digest CHAR(64);
