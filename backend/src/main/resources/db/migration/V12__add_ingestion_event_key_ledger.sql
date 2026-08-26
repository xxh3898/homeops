CREATE TABLE ingestion_event_key_ledger (
    source_type VARCHAR(16) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    CONSTRAINT pk_ingestion_event_key_ledger
        PRIMARY KEY (source_type, event_key),
    CONSTRAINT ck_ingestion_event_key_source_type
        CHECK (source_type IN ('DEPLOYMENT', 'BACKUP'))
);

INSERT INTO ingestion_event_key_ledger (source_type, event_key)
SELECT 'DEPLOYMENT', event_key
FROM deployment
ON CONFLICT DO NOTHING;

INSERT INTO ingestion_event_key_ledger (source_type, event_key)
SELECT 'BACKUP', event_key
FROM backup_run
ON CONFLICT DO NOTHING;
