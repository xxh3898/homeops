package dev.homeops.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HomeOpsControlPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ControlPropertiesConfiguration.class);

    @Test
    void should_startWithEmptyAllowlist_when_configurationIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            HomeOpsControlProperties properties = context.getBean(HomeOpsControlProperties.class);
            assertThat(properties.allowedProjectCount()).isZero();
            assertThat(properties.allows("example")).isFalse();
        });
    }

    @Test
    void should_bindExactProjects_when_configurationIsValid() {
        contextRunner.withPropertyValues(
                "homeops.control.allowed-projects=example, example-worker")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HomeOpsControlProperties properties = context.getBean(HomeOpsControlProperties.class);
                    assertThat(properties.allowedProjectCount()).isEqualTo(2);
                    assertThat(properties.allows("example")).isTrue();
                    assertThat(properties.allows("example-worker")).isTrue();
                    assertThat(properties.allows("Example")).isFalse();
                    assertThat(properties.toString())
                            .doesNotContain("example")
                            .contains("allowedProjectCount=2");
                });
    }

    @Test
    void should_failStartupWithoutExposingValue_when_configurationIsInvalid() {
        String privateProjectMarker = "PrivateProject";
        contextRunner.withPropertyValues(
                "homeops.control.allowed-projects=" + privateProjectMarker)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure != null) {
                        if (failure.getMessage() != null) {
                            assertThat(failure.getMessage()).doesNotContain(privateProjectMarker);
                        }
                        failure = failure.getCause();
                    }
                });
    }

    @Test
    void should_rejectEmptyDuplicateAndInvalidEntries_when_parsingAllowlist() {
        assertThatThrownBy(() -> new HomeOpsControlProperties("example,,worker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist contains an empty entry");
        assertThatThrownBy(() -> new HomeOpsControlProperties("example, example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist contains a duplicate entry");
        assertThatThrownBy(() -> new HomeOpsControlProperties("Example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist contains an invalid entry");
        assertThatThrownBy(() -> new HomeOpsControlProperties("example.project"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist contains an invalid entry");
    }

    @Test
    void should_rejectConfiguration_when_allowlistExceedsBounds() {
        String tooManyProjects = IntStream.range(0, 33)
                .mapToObj(index -> "project-" + index)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        assertThatThrownBy(() -> new HomeOpsControlProperties(tooManyProjects))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist exceeds the supported entry bound");
        assertThatThrownBy(() -> new HomeOpsControlProperties("a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist contains an invalid entry");
        assertThatThrownBy(() -> new HomeOpsControlProperties("a".repeat(2_049)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control project allowlist exceeds the supported size bound");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HomeOpsControlProperties.class)
    static class ControlPropertiesConfiguration { }
}
