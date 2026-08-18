package dev.homeops.agent.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ContainerLogRedactorTest {

    private final ContainerLogRedactor redactor = new ContainerLogRedactor();

    @Test
    void should_matchSharedGoAndJavaRedactionContract() throws Exception {
        Path path = Path.of("..", "contracts", "container-log-redaction-v1.json");
        Fixture fixture = new ObjectMapper().readValue(
                Files.readString(path),
                Fixture.class);

        assertThat(fixture.version()).isEqualTo(1);
        assertThat(fixture.vectors()).isNotEmpty();
        for (Vector vector : fixture.vectors()) {
            ContainerLogRedactor.SanitizedText sanitized = redactor.sanitize(
                    vector.input());
            assertThat(sanitized.text()).as(vector.name()).isEqualTo(vector.expected());
            assertThat(sanitized.redactionApplied())
                    .as(vector.name() + " redactionApplied")
                    .isEqualTo(vector.redactionApplied());
        }
    }

    private record Fixture(int version, List<Vector> vectors) {
    }

    private record Vector(
            String name,
            String input,
            String expected,
            boolean redactionApplied) {
    }
}
