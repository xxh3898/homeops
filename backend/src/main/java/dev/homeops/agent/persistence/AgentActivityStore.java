package dev.homeops.agent.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentActivityStore {
    private final JdbcTemplate jdbcTemplate;

    public AgentActivityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordConnection(String agentId, String version, Instant occurredAt, boolean versionChanged) {
        String eventType = versionChanged ? "VERSION_CHANGED" : "CONNECTED";
        String summary = versionChanged ? "Agent version changed" : "Agent connected";
        jdbcTemplate.update("""
                INSERT INTO agent_event (id, agent_id, event_type, agent_version, occurred_at, summary)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), agentId, eventType, version, Timestamp.from(occurredAt), summary);
    }
}
