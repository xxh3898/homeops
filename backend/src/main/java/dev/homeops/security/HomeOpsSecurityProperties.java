package dev.homeops.security;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.security")
public record HomeOpsSecurityProperties(
        @NotNull AuthenticationMode mode,
        List<String> allowedUsers) {

    public HomeOpsSecurityProperties {
        allowedUsers = allowedUsers == null
                ? List.of()
                : allowedUsers.stream()
                        .map(HomeOpsSecurityProperties::normalize)
                        .distinct()
                        .toList();
    }

    public boolean isAllowed(String login) {
        return login != null && allowedUsers.contains(normalize(login));
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public enum AuthenticationMode {
        TAILSCALE,
        DEV
    }
}

