package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.security.HomeOpsSecurityProperties.AuthenticationMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class SecurityModeGuardTest {

    @Test
    void should_rejectStartup_when_tailscaleAllowlistIsEmpty() {
        var guard = new SecurityModeGuard(
                new HomeOpsSecurityProperties(
                        AuthenticationMode.TAILSCALE,
                        List.of()),
                new MockEnvironment());

        assertThatThrownBy(() -> guard.run(
                new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one Tailscale user must be configured");
    }

    @Test
    void should_rejectStartup_when_devModeRunsWithoutDevProfile() {
        var guard = new SecurityModeGuard(
                new HomeOpsSecurityProperties(
                        AuthenticationMode.DEV,
                        List.of()),
                new MockEnvironment());

        assertThatThrownBy(() -> guard.run(
                new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEV authentication mode requires the dev profile");
    }
}

