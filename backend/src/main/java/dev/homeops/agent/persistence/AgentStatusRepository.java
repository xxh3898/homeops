package dev.homeops.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStatusRepository
        extends JpaRepository<AgentStatusEntity, String> {
}

