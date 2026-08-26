package dev.homeops.ingestion.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionEventKeyLedgerStore {
    private final JdbcTemplate jdbcTemplate;

    public IngestionEventKeyLedgerStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean reserve(SourceType sourceType, String eventKey) {
        return jdbcTemplate.update("""
                INSERT INTO ingestion_event_key_ledger (source_type, event_key)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, sourceType.name(), eventKey) == 1;
    }

    public enum SourceType {
        DEPLOYMENT,
        BACKUP
    }
}
