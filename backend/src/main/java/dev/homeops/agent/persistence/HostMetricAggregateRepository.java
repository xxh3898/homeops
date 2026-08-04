package dev.homeops.agent.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HostMetricAggregateRepository
        extends JpaRepository<HostMetricAggregateEntity, UUID> {

    Optional<HostMetricAggregateEntity> findByAgentIdAndBucketStart(
            String agentId,
            Instant bucketStart);

    long deleteByBucketStartBefore(Instant cutoff);
}
