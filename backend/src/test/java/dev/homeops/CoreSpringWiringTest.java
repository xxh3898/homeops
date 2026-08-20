package dev.homeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.ProcessedAgentSnapshotRetentionJob;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.logs.ContainerLogBroker;
import dev.homeops.agent.logs.ContainerLogQueryService;
import dev.homeops.agent.logs.ContainerLogRedactor;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.AgentActivityStore;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.metrics.HomeOpsMetricProperties;
import dev.homeops.metrics.HostMetricHistoryStore;
import dev.homeops.metrics.HostMetricRetentionJob;
import dev.homeops.metrics.MetricHistoryService;
import dev.homeops.notification.AgentLifecycleNotificationProducer;
import dev.homeops.notification.ContainerNotificationProducer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

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
                    AgentStatusStore.class,
                    () -> mock(AgentStatusStore.class));
            context.registerBean(
                    HostMetricAggregateRepository.class,
                    () -> mock(HostMetricAggregateRepository.class));
            context.registerBean(
                    ProcessedAgentSnapshotStore.class,
                    () -> mock(ProcessedAgentSnapshotStore.class));
            context.registerBean(
                    AgentActivityStore.class,
                    () -> mock(AgentActivityStore.class));
            context.registerBean(
                    AgentLifecycleNotificationProducer.class,
                    () -> mock(AgentLifecycleNotificationProducer.class));
            context.registerBean(
                    ContainerNotificationProducer.class,
                    () -> mock(ContainerNotificationProducer.class));
            context.registerBean(
                    JdbcTemplate.class,
                    () -> mock(JdbcTemplate.class));
            context.register(
                    AgentSnapshotService.class,
                    ContainerLogRedactor.class,
                    ContainerLogBroker.class,
                    ContainerLogQueryService.class,
                    ProcessedAgentSnapshotRetentionJob.class,
                    HostMetricRetentionJob.class,
                    HostMetricHistoryStore.class,
                    MetricHistoryService.class);

            context.refresh();

            assertThat(context.getBean(AgentSnapshotService.class)).isNotNull();
            assertThat(context.getBean(ContainerLogBroker.class)).isNotNull();
            assertThat(context.getBean(ContainerLogQueryService.class)).isNotNull();
            assertThat(context.getBean(ProcessedAgentSnapshotRetentionJob.class))
                    .isNotNull();
            assertThat(context.getBean(HostMetricRetentionJob.class)).isNotNull();
            assertThat(context.getBean(MetricHistoryService.class)).isNotNull();
        }
    }
}
