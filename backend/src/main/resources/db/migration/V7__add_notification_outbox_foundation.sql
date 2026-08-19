ALTER TABLE notification_event
    DROP CONSTRAINT ck_notification_status;

ALTER TABLE notification_event
    ADD COLUMN canonical_deduplication_hash VARCHAR(64),
    ADD COLUMN source_type VARCHAR(32),
    ADD COLUMN source_id UUID,
    ADD COLUMN payload JSONB,
    ADD COLUMN parent_notification_id UUID,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN failure_code VARCHAR(64),
    ADD COLUMN created_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN terminal_at TIMESTAMPTZ;

UPDATE notification_event
SET created_at = occurred_at,
    updated_at = COALESCE(sent_at, occurred_at),
    terminal_at = CASE
        WHEN status IN ('SENT', 'FAILED', 'SUPPRESSED')
            THEN COALESCE(sent_at, occurred_at)
        ELSE NULL
    END;

ALTER TABLE notification_event
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT fk_notification_parent
        FOREIGN KEY (parent_notification_id)
        REFERENCES notification_event(id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_notification_status
        CHECK (status IN (
            'PENDING', 'DELIVERING', 'SENT', 'FAILED',
            'DELIVERY_UNKNOWN', 'SUPPRESSED'
        )),
    ADD CONSTRAINT ck_notification_attempt_count
        CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_notification_canonical_hash
        CHECK (
            canonical_deduplication_hash IS NULL
            OR canonical_deduplication_hash ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT ck_notification_source_type
        CHECK (
            source_type IS NULL
            OR source_type IN ('DEPLOYMENT', 'BACKUP', 'INCIDENT', 'AGENT', 'CONTAINER')
        ),
    ADD CONSTRAINT ck_notification_failure_code
        CHECK (
            failure_code IS NULL
            OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
        ),
    ADD CONSTRAINT ck_notification_payload
        CHECK (
            payload IS NULL
            OR (
                jsonb_typeof(payload) = 'object'
                AND octet_length(payload::text) <= 8192
            )
        ),
    ADD CONSTRAINT ck_notification_foundation_row
        CHECK (
            (
                canonical_deduplication_hash IS NULL
                AND source_type IS NULL
                AND source_id IS NULL
                AND payload IS NULL
            )
            OR (
                canonical_deduplication_hash IS NOT NULL
                AND source_type IS NOT NULL
                AND source_id IS NOT NULL
                AND payload IS NOT NULL
            )
        ),
    ADD CONSTRAINT ck_notification_delivery_lease
        CHECK (
            (
                status = 'DELIVERING'
                AND lease_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
            )
            OR (
                status <> 'DELIVERING'
                AND lease_token IS NULL
                AND lease_expires_at IS NULL
            )
        ),
    ADD CONSTRAINT ck_notification_terminal_at
        CHECK (
            (
                status IN ('SENT', 'FAILED', 'DELIVERY_UNKNOWN', 'SUPPRESSED')
                AND terminal_at IS NOT NULL
            )
            OR (
                status NOT IN ('SENT', 'FAILED', 'DELIVERY_UNKNOWN', 'SUPPRESSED')
                AND terminal_at IS NULL
            )
        ),
    ADD CONSTRAINT ck_notification_pending_due
        CHECK (
            canonical_deduplication_hash IS NULL
            OR status <> 'PENDING'
            OR next_attempt_at IS NOT NULL
        );

CREATE UNIQUE INDEX uk_notification_channel_canonical_dedup
    ON notification_event (channel, canonical_deduplication_hash)
    WHERE canonical_deduplication_hash IS NOT NULL;

CREATE INDEX ix_notification_pending_due
    ON notification_event (next_attempt_at, occurred_at, id)
    WHERE canonical_deduplication_hash IS NOT NULL AND status = 'PENDING';

CREATE INDEX ix_notification_delivering_lease
    ON notification_event (lease_expires_at, occurred_at, id)
    WHERE canonical_deduplication_hash IS NOT NULL AND status = 'DELIVERING';

CREATE INDEX ix_notification_terminal_retention
    ON notification_event (status, terminal_at, id)
    WHERE canonical_deduplication_hash IS NOT NULL
      AND status IN ('SENT', 'FAILED', 'DELIVERY_UNKNOWN', 'SUPPRESSED');
