ALTER TABLE container_action_audit
    ADD COLUMN reason_code VARCHAR(64),
    ALTER COLUMN container_name DROP NOT NULL;

ALTER TABLE container_action_audit
    DROP CONSTRAINT ck_container_action_result;

UPDATE container_action_audit
SET result = CASE result
        WHEN 'SUCCESS' THEN 'APPLIED'
        WHEN 'REJECTED' THEN 'DENIED'
        WHEN 'TIMED_OUT' THEN 'OUTCOME_UNKNOWN'
        ELSE result
    END,
    reason_code = CASE result
        WHEN 'SUCCESS' THEN 'LEGACY_SUCCESS'
        WHEN 'REJECTED' THEN 'LEGACY_REJECTED'
        WHEN 'TIMED_OUT' THEN 'LEGACY_TIMED_OUT'
        WHEN 'FAILED' THEN 'LEGACY_FAILED'
        ELSE NULL
    END,
    completed_at = CASE
        WHEN result = 'REQUESTED' THEN completed_at
        ELSE COALESCE(completed_at, requested_at)
    END;

ALTER TABLE container_action_audit
    ADD CONSTRAINT ck_container_action_result
        CHECK (result IN (
            'REQUESTED', 'APPLIED', 'NOOP', 'DENIED',
            'FAILED', 'OUTCOME_UNKNOWN', 'EXPIRED'
        )),
    ADD CONSTRAINT ck_container_action_identifier_v10
        CHECK (container_id_prefix ~ '^[0-9a-f]{12}$') NOT VALID,
    ADD CONSTRAINT ck_container_action_reason_v10
        CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$') NOT VALID,
    ADD CONSTRAINT ck_container_action_terminal_shape_v10
        CHECK (
            (result = 'REQUESTED' AND completed_at IS NULL AND reason_code IS NULL)
            OR
            (result <> 'REQUESTED' AND completed_at IS NOT NULL AND reason_code IS NOT NULL)
        ) NOT VALID;

CREATE INDEX ix_container_action_result_requested
    ON container_action_audit (result, requested_at);
