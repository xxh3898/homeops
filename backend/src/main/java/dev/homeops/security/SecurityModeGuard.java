package dev.homeops.security;

import dev.homeops.security.HomeOpsSecurityProperties.AuthenticationMode;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SecurityModeGuard implements ApplicationRunner {

    private final HomeOpsSecurityProperties properties;
    private final Environment environment;

    public SecurityModeGuard(
            HomeOpsSecurityProperties properties,
            Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.mode() == AuthenticationMode.TAILSCALE
                && properties.allowedUsers().isEmpty()) {
            throw new IllegalStateException(
                    "At least one Tailscale user must be configured");
        }
        if (properties.mode() == AuthenticationMode.DEV
                && Arrays.stream(environment.getActiveProfiles())
                        .noneMatch("dev"::equals)) {
            throw new IllegalStateException(
                    "DEV authentication mode requires the dev profile");
        }
    }
}

