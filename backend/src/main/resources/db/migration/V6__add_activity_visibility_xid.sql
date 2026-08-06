ALTER TABLE deployment
    ADD COLUMN recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id();

ALTER TABLE backup_run
    ADD COLUMN recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id();

ALTER TABLE incident
    ADD COLUMN recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    ADD COLUMN resolved_xid XID8;

ALTER TABLE agent_event
    ADD COLUMN recorded_xid XID8 NOT NULL DEFAULT pg_current_xact_id();
