CREATE TABLE agent_status (
    agent_id VARCHAR(64) PRIMARY KEY,
    agent_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_snapshot_id UUID,
    last_captured_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_status_state
        CHECK (status IN ('CONNECTED', 'STALE', 'OFFLINE', 'UNKNOWN'))
);

CREATE TABLE host_metric_aggregate (
    id UUID PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    sample_count INTEGER NOT NULL,
    cpu_usage_average DOUBLE PRECISION NOT NULL,
    cpu_usage_peak DOUBLE PRECISION NOT NULL,
    memory_total_bytes BIGINT NOT NULL,
    memory_used_average_bytes BIGINT NOT NULL,
    memory_used_peak_bytes BIGINT NOT NULL,
    disk_total_bytes BIGINT NOT NULL,
    disk_used_bytes BIGINT NOT NULL,
    CONSTRAINT uk_host_metric_agent_bucket
        UNIQUE (agent_id, bucket_start),
    CONSTRAINT ck_host_metric_sample_count CHECK (sample_count > 0),
    CONSTRAINT ck_host_metric_cpu_average
        CHECK (cpu_usage_average BETWEEN 0 AND 100),
    CONSTRAINT ck_host_metric_cpu_peak
        CHECK (cpu_usage_peak BETWEEN 0 AND 100)
);

CREATE INDEX ix_host_metric_bucket
    ON host_metric_aggregate (bucket_start DESC);

CREATE TABLE monitored_service (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    expected_status INTEGER NOT NULL,
    timeout_ms INTEGER NOT NULL,
    interval_seconds INTEGER NOT NULL,
    failure_threshold INTEGER NOT NULL,
    recovery_threshold INTEGER NOT NULL,
    severity VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notification_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_monitored_service_name UNIQUE (name),
    CONSTRAINT ck_monitored_service_method
        CHECK (http_method IN ('GET', 'HEAD')),
    CONSTRAINT ck_monitored_service_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE TABLE health_check_result (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES monitored_service(id),
    checked_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    http_status INTEGER,
    response_time_ms INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(512),
    CONSTRAINT ck_health_check_status
        CHECK (status IN ('HEALTHY', 'DEGRADED', 'DOWN', 'MAINTENANCE', 'DISABLED'))
);

CREATE INDEX ix_health_check_service_checked
    ON health_check_result (service_id, checked_at DESC);
CREATE INDEX ix_health_check_failure_checked
    ON health_check_result (checked_at DESC)
    WHERE status IN ('DEGRADED', 'DOWN');

CREATE TABLE incident (
    id UUID PRIMARY KEY,
    service_id UUID REFERENCES monitored_service(id),
    incident_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    title VARCHAR(256) NOT NULL,
    summary VARCHAR(1024),
    opened_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_incident_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_incident_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'))
);

CREATE INDEX ix_incident_opened ON incident (status, opened_at DESC);

CREATE TABLE deployment (
    id UUID PRIMARY KEY,
    event_key VARCHAR(128) NOT NULL,
    project VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    branch VARCHAR(128),
    commit_sha CHAR(40) NOT NULL,
    image_tag VARCHAR(256),
    previous_commit_sha CHAR(40),
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    failure_stage VARCHAR(128),
    failure_summary VARCHAR(1024),
    actor VARCHAR(128),
    workflow_run_id VARCHAR(64),
    workflow_run_url VARCHAR(2048),
    rollback BOOLEAN NOT NULL DEFAULT FALSE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_deployment_event_key UNIQUE (event_key),
    CONSTRAINT ck_deployment_status
        CHECK (status IN ('REQUESTED', 'RUNNING', 'SUCCESS', 'FAILED', 'ROLLED_BACK', 'CANCELLED'))
);

CREATE INDEX ix_deployment_project_started
    ON deployment (project, started_at DESC);

CREATE TABLE backup_run (
    id UUID PRIMARY KEY,
    event_key VARCHAR(128) NOT NULL,
    project VARCHAR(128) NOT NULL,
    database_type VARCHAR(32) NOT NULL,
    logical_location VARCHAR(256),
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    size_bytes BIGINT,
    expires_at TIMESTAMPTZ,
    failure_summary VARCHAR(1024),
    restore_tested_at TIMESTAMPTZ,
    restore_test_status VARCHAR(24),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_backup_run_event_key UNIQUE (event_key),
    CONSTRAINT ck_backup_run_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'INCOMPLETE'))
);

CREATE INDEX ix_backup_run_project_started
    ON backup_run (project, started_at DESC);

CREATE TABLE notification_event (
    id UUID PRIMARY KEY,
    deduplication_key VARCHAR(256) NOT NULL,
    incident_id UUID REFERENCES incident(id),
    channel VARCHAR(24) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    failure_summary VARCHAR(512),
    CONSTRAINT ck_notification_channel
        CHECK (channel IN ('DISCORD', 'EMAIL')),
    CONSTRAINT ck_notification_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT ck_notification_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'RECOVERY'))
);

CREATE INDEX ix_notification_deduplication
    ON notification_event (deduplication_key, occurred_at DESC);

CREATE TABLE container_action_audit (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    principal VARCHAR(256) NOT NULL,
    action VARCHAR(16) NOT NULL,
    container_id_prefix VARCHAR(64) NOT NULL,
    container_name VARCHAR(128) NOT NULL,
    image VARCHAR(512),
    result VARCHAR(24) NOT NULL,
    failure_summary VARCHAR(512),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_container_action_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_container_action
        CHECK (action IN ('START', 'STOP', 'RESTART')),
    CONSTRAINT ck_container_action_result
        CHECK (result IN ('REQUESTED', 'SUCCESS', 'FAILED', 'REJECTED', 'TIMED_OUT'))
);

CREATE INDEX ix_container_action_requested
    ON container_action_audit (requested_at DESC);

CREATE TABLE app_setting (
    setting_key VARCHAR(128) PRIMARY KEY,
    value_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(256) NOT NULL,
    CONSTRAINT ck_app_setting_no_secret_keys
        CHECK (setting_key !~* '(password|secret|token|webhook|credential)')
);

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INTEGER NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK
        PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID)
        ON DELETE CASCADE
);

