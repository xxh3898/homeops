package dev.homeops.monitoring;

import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ServiceCheckExecutorConfiguration {

    @Bean(name = "serviceCheckExecutor", destroyMethod = "shutdown")
    Executor serviceCheckExecutor(HomeOpsMonitoringProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrentChecks());
        executor.setMaxPoolSize(properties.maxConcurrentChecks());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("homeops-service-check-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
