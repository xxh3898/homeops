package dev.homeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.ProcessedAgentSnapshotRetentionJob;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.metrics.HomeOpsMetricProperties;
import dev.homeops.metrics.HostMetricRetentionJob;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CoreSpringWiringTest {

    @Test
    void should_createCoreBeans_when_requiredDependenciesAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    HomeOpsAgentProperties.class,
                    () -> new HomeOpsAgentProperties(
                            "local-mac",
                            Duration.ofSeconds(30),
                            Duration.ofMinutes(5),
                            Duration.ofMinutes(1),
                            128,
                            Duration.ofDays(1)));
            context.registerBean(
                    HomeOpsMetricProperties.class,
                    () -> new HomeOpsMetricProperties(Duration.ofDays(30)));
            context.registerBean(
                    AgentStatusRepository.class,
                    () -> mock(AgentStatusRepository.class));
            context.registerBean(
                    HostMetricAggregateRepository.class,
                    () -> mock(HostMetricAggregateRepository.class));
            context.registerBean(
                    ProcessedAgentSnapshotStore.class,
                    () -> mock(ProcessedAgentSnapshotStore.class));
            context.register(
                    AgentSnapshotService.class,
                    ProcessedAgentSnapshotRetentionJob.class,
                    HostMetricRetentionJob.class);

            context.refresh();

            assertThat(context.getBean(AgentSnapshotService.class)).isNotNull();
            assertThat(context.getBean(ProcessedAgentSnapshotRetentionJob.class))
                    .isNotNull();
            assertThat(context.getBean(HostMetricRetentionJob.class)).isNotNull();
        }
    }
}
