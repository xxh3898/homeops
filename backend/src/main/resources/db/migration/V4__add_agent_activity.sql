CREATE TABLE agent_event (
    id UUID PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    agent_version VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    summary VARCHAR(256) NOT NULL,
    CONSTRAINT ck_agent_event_type
        CHECK (event_type IN ('CONNECTED', 'VERSION_CHANGED'))
);

CREATE INDEX ix_agent_event_occurred
    ON agent_event (occurred_at DESC, id DESC);

CREATE UNIQUE INDEX uk_incident_service_open
    ON incident (service_id)
    WHERE service_id IS NOT NULL AND status IN ('OPEN', 'ACKNOWLEDGED');
