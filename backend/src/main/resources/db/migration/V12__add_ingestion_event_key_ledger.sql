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

CREATE FUNCTION reserve_ingestion_event_key_before_business_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO ingestion_event_key_ledger (source_type, event_key)
    VALUES (TG_ARGV[0], NEW.event_key)
    ON CONFLICT DO NOTHING;

    IF FOUND THEN
        RETURN NEW;
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_deployment_reserve_ingestion_event_key
BEFORE INSERT ON deployment
FOR EACH ROW
EXECUTE FUNCTION reserve_ingestion_event_key_before_business_insert('DEPLOYMENT');

CREATE TRIGGER trg_backup_run_reserve_ingestion_event_key
BEFORE INSERT ON backup_run
FOR EACH ROW
EXECUTE FUNCTION reserve_ingestion_event_key_before_business_insert('BACKUP');
