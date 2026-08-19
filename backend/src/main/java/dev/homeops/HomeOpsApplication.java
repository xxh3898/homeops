package dev.homeops;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.metrics.HomeOpsMetricProperties;
import dev.homeops.ingestion.config.HomeOpsIngestionProperties;
import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import dev.homeops.notification.config.IncidentNotificationProperties;
import dev.homeops.security.HomeOpsSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({
    HomeOpsAgentProperties.class,
    HomeOpsMetricProperties.class,
    HomeOpsIngestionProperties.class,
    HomeOpsMonitoringProperties.class,
    IncidentNotificationProperties.class,
    HomeOpsNotificationProperties.class,
    HomeOpsSecurityProperties.class
})
public class HomeOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomeOpsApplication.class, args);
    }
}
