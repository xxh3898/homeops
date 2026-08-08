ALTER TABLE deployment
    ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE backup_run
    ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE incident
    ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE agent_event
    ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX ix_deployment_activity_recorded
    ON deployment (recorded_at DESC, started_at DESC);

CREATE INDEX ix_backup_activity_recorded
    ON backup_run (recorded_at DESC, started_at DESC);

CREATE INDEX ix_incident_activity_recorded
    ON incident (recorded_at DESC, opened_at DESC);

CREATE INDEX ix_agent_event_activity_recorded
    ON agent_event (recorded_at DESC, occurred_at DESC);
