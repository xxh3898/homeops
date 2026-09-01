CREATE TABLE automatic_recovery_mapping (
    service_id UUID PRIMARY KEY REFERENCES monitored_service(id) ON DELETE RESTRICT,
    project VARCHAR(32) NOT NULL,
    target VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_reserved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_automatic_recovery_mapping_identity
        UNIQUE (service_id, project, target),
    CONSTRAINT uk_automatic_recovery_mapping_target
        UNIQUE (project, target),
    CONSTRAINT ck_automatic_recovery_mapping_project
        CHECK (project = 'rhaomi'),
    CONSTRAINT ck_automatic_recovery_mapping_target
        CHECK (target IN ('rhaomi-web', 'backend'))
);

CREATE TABLE automatic_recovery_attempt (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE RESTRICT,
    service_id UUID NOT NULL REFERENCES monitored_service(id) ON DELETE RESTRICT,
    project VARCHAR(32),
    target VARCHAR(64),
    action VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    pre_health VARCHAR(16),
    post_health VARCHAR(16),
    restart_count INTEGER,
    recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    CONSTRAINT uk_automatic_recovery_attempt_incident UNIQUE (incident_id),
    CONSTRAINT fk_automatic_recovery_attempt_mapping
        FOREIGN KEY (service_id, project, target)
        REFERENCES automatic_recovery_mapping (service_id, project, target)
        ON DELETE RESTRICT,
    CONSTRAINT ck_automatic_recovery_attempt_target_shape
        CHECK (
            (project IS NULL AND target IS NULL)
            OR (project = 'rhaomi' AND target IN ('rhaomi-web', 'backend'))
        ),
    CONSTRAINT ck_automatic_recovery_attempt_action
        CHECK (action = 'RESTART'),
    CONSTRAINT ck_automatic_recovery_attempt_status
        CHECK (status IN (
            'REQUESTED', 'DISPATCHED', 'APPLIED', 'SKIPPED',
            'FAILED', 'OUTCOME_UNKNOWN', 'EXPIRED'
        )),
    CONSTRAINT ck_automatic_recovery_attempt_reason
        CHECK (reason_code IS NULL OR reason_code IN (
            'TARGET_UNMAPPED', 'AUTHORITY_DISABLED', 'COOLDOWN_ACTIVE',
            'INCIDENT_NOT_OPEN', 'REQUEST_EXPIRED', 'CAPABILITY_UNAVAILABLE',
            'BROKER_BUSY', 'RECOVERY_APPLIED', 'RECOVERY_INPUT_INVALID',
            'RECOVERY_LOCKED', 'RECOVERY_LOCK_INVALID',
            'RECOVERY_LOCK_RELEASE_FAILED', 'RECOVERY_TARGET_INVALID',
            'RECOVERY_TARGET_UNAVAILABLE', 'RECOVERY_IDENTITY_CHANGED',
            'RECOVERY_POST_HEALTH_FAILED', 'RECOVERY_RESTART_UNCONFIRMED',
            'RECOVERY_FAILED', 'CAPABILITY_RESULT_INVALID',
            'CAPABILITY_TIMEOUT', 'RESULT_UNAVAILABLE', 'WORK_EXPIRED'
        )),
    CONSTRAINT ck_automatic_recovery_attempt_health
        CHECK (
            (pre_health IS NULL OR pre_health IN ('UP', 'DOWN', 'UNKNOWN'))
            AND (post_health IS NULL OR post_health IN ('UP', 'DOWN', 'UNKNOWN'))
        ),
    CONSTRAINT ck_automatic_recovery_attempt_restart_count
        CHECK (restart_count IS NULL OR restart_count BETWEEN 0 AND 1),
    CONSTRAINT ck_automatic_recovery_attempt_timestamps
        CHECK (
            (dispatched_at IS NULL OR dispatched_at >= requested_at)
            AND (started_at IS NULL OR started_at >= requested_at - INTERVAL '1 second')
            AND (completed_at IS NULL OR completed_at >= requested_at - INTERVAL '1 second')
            AND (started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at)
        ),
    CONSTRAINT ck_automatic_recovery_attempt_state_shape
        CHECK (
            (status = 'REQUESTED'
                AND reason_code IS NULL
                AND dispatched_at IS NULL
                AND started_at IS NULL
                AND completed_at IS NULL
                AND pre_health IS NULL
                AND post_health IS NULL
                AND restart_count IS NULL)
            OR
            (status = 'DISPATCHED'
                AND reason_code IS NULL
                AND dispatched_at IS NOT NULL
                AND completed_at IS NULL)
            OR
            (status NOT IN ('REQUESTED', 'DISPATCHED')
                AND reason_code IS NOT NULL
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_automatic_recovery_attempt_terminal_reason
        CHECK (
            status IN ('REQUESTED', 'DISPATCHED')
            OR (status = 'APPLIED' AND reason_code = 'RECOVERY_APPLIED')
            OR (status = 'SKIPPED' AND reason_code IN (
                'TARGET_UNMAPPED', 'AUTHORITY_DISABLED', 'COOLDOWN_ACTIVE',
                'INCIDENT_NOT_OPEN', 'CAPABILITY_UNAVAILABLE', 'BROKER_BUSY',
                'RECOVERY_INPUT_INVALID', 'RECOVERY_LOCKED',
                'RECOVERY_LOCK_INVALID', 'RECOVERY_TARGET_INVALID',
                'RECOVERY_TARGET_UNAVAILABLE'
            ))
            OR (status = 'FAILED' AND reason_code IN (
                'RECOVERY_LOCK_RELEASE_FAILED', 'RECOVERY_IDENTITY_CHANGED',
                'RECOVERY_POST_HEALTH_FAILED', 'RECOVERY_FAILED',
                'CAPABILITY_UNAVAILABLE'
            ))
            OR (status = 'OUTCOME_UNKNOWN' AND reason_code IN (
                'RECOVERY_RESTART_UNCONFIRMED', 'CAPABILITY_RESULT_INVALID',
                'CAPABILITY_TIMEOUT', 'RESULT_UNAVAILABLE'
            ))
            OR (status = 'EXPIRED' AND reason_code IN (
                'REQUEST_EXPIRED', 'WORK_EXPIRED'
            ))
        ),
    CONSTRAINT ck_automatic_recovery_attempt_unmapped_shape
        CHECK (
            project IS NOT NULL
            OR (
                status = 'SKIPPED'
                AND reason_code IN ('TARGET_UNMAPPED', 'INCIDENT_NOT_OPEN')
                AND restart_count = 0
            )
        ),
    CONSTRAINT ck_automatic_recovery_attempt_applied
        CHECK (
            status <> 'APPLIED'
            OR (
                project = 'rhaomi'
                AND target IS NOT NULL
                AND reason_code = 'RECOVERY_APPLIED'
                AND restart_count = 1
                AND post_health = 'UP'
            )
        )
);

CREATE INDEX ix_automatic_recovery_attempt_status_requested
    ON automatic_recovery_attempt (status, requested_at, id);

CREATE INDEX ix_automatic_recovery_attempt_target_requested
    ON automatic_recovery_attempt (project, target, requested_at DESC)
    WHERE project IS NOT NULL AND target IS NOT NULL;
