ALTER TABLE ingestion_event_key_ledger
    DROP CONSTRAINT ck_ingestion_event_key_source_type;

ALTER TABLE ingestion_event_key_ledger
    ADD CONSTRAINT ck_ingestion_event_key_source_type
        CHECK (source_type IN ('DEPLOYMENT', 'BACKUP', 'SIGNAL'));

CREATE TABLE monitoring_signal_episode (
    id UUID PRIMARY KEY,
    episode_key VARCHAR(128) NOT NULL,
    project VARCHAR(128) NOT NULL,
    signal_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE RESTRICT,
    alerted_at TIMESTAMPTZ NOT NULL,
    recovered_at TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_monitoring_signal_episode_key UNIQUE (episode_key),
    CONSTRAINT uk_monitoring_signal_episode_incident UNIQUE (incident_id),
    CONSTRAINT uk_monitoring_signal_episode_identity UNIQUE (id, project, signal_type),
    CONSTRAINT ck_monitoring_signal_episode_key
        CHECK (episode_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_monitoring_signal_episode_project
        CHECK (project ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_monitoring_signal_episode_type
        CHECK (signal_type IN ('DISK_LOW', 'HTTP_5XX_BURST')),
    CONSTRAINT ck_monitoring_signal_episode_status
        CHECK (status IN ('ACTIVE', 'RECOVERED')),
    CONSTRAINT ck_monitoring_signal_episode_timestamps
        CHECK (
            last_observed_at >= alerted_at
            AND (
                (status = 'ACTIVE' AND recovered_at IS NULL)
                OR (status = 'RECOVERED' AND recovered_at IS NOT NULL
                    AND recovered_at >= alerted_at
                    AND last_observed_at = recovered_at)
            )
        )
);

CREATE UNIQUE INDEX uk_monitoring_signal_project_type_active
    ON monitoring_signal_episode (project, signal_type)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_monitoring_signal_episode_recent
    ON monitoring_signal_episode (last_observed_at DESC, id DESC);

CREATE TABLE monitoring_signal_event (
    id UUID PRIMARY KEY,
    event_key VARCHAR(128) NOT NULL,
    episode_id UUID NOT NULL,
    project VARCHAR(128) NOT NULL,
    signal_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    available_percent NUMERIC(5, 2),
    threshold_percent NUMERIC(5, 2),
    observed_count INTEGER,
    window_seconds INTEGER,
    threshold_count INTEGER,
    ingestion_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_monitoring_signal_event_key UNIQUE (event_key),
    CONSTRAINT uk_monitoring_signal_event_episode_status UNIQUE (episode_id, status),
    CONSTRAINT fk_monitoring_signal_event_episode
        FOREIGN KEY (episode_id, project, signal_type)
        REFERENCES monitoring_signal_episode (id, project, signal_type)
        ON DELETE RESTRICT,
    CONSTRAINT ck_monitoring_signal_event_key
        CHECK (event_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_monitoring_signal_event_project
        CHECK (project ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_monitoring_signal_event_type
        CHECK (signal_type IN ('DISK_LOW', 'HTTP_5XX_BURST')),
    CONSTRAINT ck_monitoring_signal_event_status
        CHECK (status IN ('ALERT', 'RECOVERED')),
    CONSTRAINT ck_monitoring_signal_event_digest
        CHECK (ingestion_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_monitoring_signal_event_measurement
        CHECK (
            (signal_type = 'DISK_LOW'
                AND available_percent IS NOT NULL
                AND available_percent BETWEEN 0 AND 100
                AND threshold_percent IS NOT NULL
                AND threshold_percent > 0
                AND threshold_percent <= 100
                AND observed_count IS NULL
                AND window_seconds IS NULL
                AND threshold_count IS NULL)
            OR
            (signal_type = 'HTTP_5XX_BURST'
                AND available_percent IS NULL
                AND threshold_percent IS NULL
                AND observed_count IS NOT NULL
                AND observed_count BETWEEN 0 AND 1000000
                AND window_seconds IS NOT NULL
                AND window_seconds BETWEEN 1 AND 86400
                AND threshold_count IS NOT NULL
                AND threshold_count BETWEEN 1 AND 1000000)
        )
);

CREATE INDEX ix_monitoring_signal_event_episode_observed
    ON monitoring_signal_event (episode_id, observed_at DESC);

CREATE TRIGGER trg_monitoring_signal_event_reserve_ingestion_event_key
BEFORE INSERT ON monitoring_signal_event
FOR EACH ROW
EXECUTE FUNCTION reserve_ingestion_event_key_before_business_insert('SIGNAL');
