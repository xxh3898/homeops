package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ContainerActionComponentWiringTest {

    @Test
    void should_createPublicControlAuditComponents_when_dependenciesAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
            context.registerBean(
                    ContainerControlBroker.class,
                    () -> mock(ContainerControlBroker.class));
            context.register(
                    ContainerActionAuditStore.class,
                    ContainerActionAuditTransactions.class,
                    ContainerActionRateLimiter.class,
                    ContainerControlAuditExecutorConfiguration.class,
                    ContainerActionService.class,
                    ContainerActionReconciliationJob.class);

            context.refresh();

            assertThat(context.getBean(ContainerActionService.class)).isNotNull();
            assertThat(context.getBean(ContainerActionReconciliationJob.class)).isNotNull();
            assertThat(context.getBean("containerControlAuditExecutor")).isNotNull();
        }
    }
}
