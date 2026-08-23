ALTER TABLE container_action_audit
    ADD COLUMN recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id();
