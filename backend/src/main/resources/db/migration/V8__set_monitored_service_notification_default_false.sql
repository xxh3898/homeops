ALTER TABLE monitored_service
    ALTER COLUMN notification_enabled SET DEFAULT FALSE;

UPDATE monitored_service
SET notification_enabled = FALSE
WHERE notification_enabled = TRUE;
