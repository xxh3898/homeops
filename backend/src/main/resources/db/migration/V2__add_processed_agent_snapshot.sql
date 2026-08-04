CREATE TABLE processed_agent_snapshot (
    agent_id VARCHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_processed_agent_snapshot
        PRIMARY KEY (agent_id, snapshot_id)
);

CREATE INDEX ix_processed_agent_snapshot_processed
    ON processed_agent_snapshot (processed_at);
