CREATE TABLE container_notification_state (
    id UUID PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    logical_identity_hash CHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    compose_project VARCHAR(128),
    instance_fingerprint CHAR(64) NOT NULL,
    notifications_allowed BOOLEAN NOT NULL,
    state VARCHAR(16) NOT NULL,
    health VARCHAR(16) NOT NULL,
    last_snapshot_id UUID NOT NULL,
    last_captured_at TIMESTAMPTZ NOT NULL,
    failure_started_at TIMESTAMPTZ,
    active_episode_id UUID,
    last_root_created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_container_notification_agent_identity
        UNIQUE (agent_id, logical_identity_hash),
    CONSTRAINT ck_container_notification_logical_hash
        CHECK (logical_identity_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_container_notification_instance_fingerprint
        CHECK (instance_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_container_notification_state
        CHECK (state IN (
            'CREATED', 'RUNNING', 'PAUSED', 'RESTARTING',
            'REMOVING', 'EXITED', 'DEAD', 'UNKNOWN'
        )),
    CONSTRAINT ck_container_notification_health
        CHECK (health IN ('HEALTHY', 'UNHEALTHY', 'STARTING', 'NONE', 'UNKNOWN')),
    CONSTRAINT ck_container_notification_authority
        CHECK (
            notifications_allowed
            OR (failure_started_at IS NULL AND active_episode_id IS NULL)
        ),
    CONSTRAINT ck_container_notification_episode
        CHECK (active_episode_id IS NULL OR failure_started_at IS NOT NULL),
    CONSTRAINT ck_container_notification_failure_time
        CHECK (failure_started_at IS NULL OR failure_started_at <= last_captured_at),
    CONSTRAINT ck_container_notification_root_time
        CHECK (last_root_created_at IS NULL OR last_root_created_at <= last_captured_at)
);
