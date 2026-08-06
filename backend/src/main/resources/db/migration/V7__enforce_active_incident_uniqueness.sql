CREATE UNIQUE INDEX ux_incident_active_service
    ON incident (service_id)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');
