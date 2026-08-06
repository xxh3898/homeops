package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeServiceUrlPolicyTest {
    private final SafeServiceUrlPolicy policy = new SafeServiceUrlPolicy(new HomeOpsMonitoringProperties(
            List.of("https://homeops.example.ts.net:9443"), Duration.ofDays(7), Duration.ofDays(30), 4));

    @Test
    void should_allowHttpsMagicDnsOrigin() {
        assertThatCode(() -> policy.validate("https://homeops.example.ts.net:9443/actuator/health?group=readiness"))
                .doesNotThrowAnyException();
    }

    @Test
    void should_rejectNonMagicDnsAndUnsafeOriginShapes() {
        for (String value : new String[] {"http://homeops.example.ts.net:9443",
                "https://127.0.0.1/", "https://user@homeops.example.ts.net:9443/",
                "https://other.example.ts.net:9443/", "https://homeops.example.ts.net:9443/#fragment",
                "https://homeops.example.ts.net:65536/"}) {
            assertThatThrownBy(() -> policy.validate(value))
                    .isInstanceOf(SafeServiceUrlPolicy.UnsafeServiceUrlException.class);
        }
    }

    @Test
    void should_rejectOutOfRangePort_when_normalizingAllowlist() {
        assertThatThrownBy(() -> new SafeServiceUrlPolicy(new HomeOpsMonitoringProperties(
                List.of("https://homeops.example.ts.net:65536"), Duration.ofDays(7), Duration.ofDays(30), 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectEveryOrigin_when_allowlistIsEmpty() {
        SafeServiceUrlPolicy emptyPolicy = new SafeServiceUrlPolicy(new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(7), Duration.ofDays(30), 4));

        assertThatThrownBy(() -> emptyPolicy.validate("https://homeops.example.ts.net:9443/health"))
                .isInstanceOf(SafeServiceUrlPolicy.UnsafeServiceUrlException.class);
    }
}
