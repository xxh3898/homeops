package dev.homeops.notification.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscordWebhookEndpointTest {
    private static final String TOKEN = "synthetic_token_" + "x".repeat(48);
    private static final String VALID = "https://discord.com/api/webhooks/123456789012345678/" + TOKEN;

    @Test
    void should_deriveWaitTrueExecutionUri_when_webhookIsStrictlyValid() {
        DiscordWebhookEndpoint endpoint = DiscordWebhookEndpoint.parse(VALID);

        assertThat(endpoint.executionUri().getScheme()).isEqualTo("https");
        assertThat(endpoint.executionUri().getHost()).isEqualTo("discord.com");
        assertThat(endpoint.executionUri().getRawQuery()).isEqualTo("wait=true");
        assertThat(endpoint.toString()).doesNotContain(TOKEN);
    }

    @Test
    void should_rejectWebhook_when_anyUrlBoundaryIsNotAllowlisted() {
        List<String> invalid = List.of(
                "http://discord.com/api/webhooks/123456789012345678/" + TOKEN,
                "https://example.com/api/webhooks/123456789012345678/" + TOKEN,
                "https://discord.com:443/api/webhooks/123456789012345678/" + TOKEN,
                "https://user@discord.com/api/webhooks/123456789012345678/" + TOKEN,
                VALID + "?wait=false",
                VALID + "#fragment",
                "https://discord.com/api/v10/webhooks/123456789012345678/" + TOKEN,
                "https://discord.com/api/webhooks/not-a-snowflake/" + TOKEN,
                "https://discord.com/api/webhooks/123456789012345678/short");

        for (String value : invalid) {
            assertThatThrownBy(() -> DiscordWebhookEndpoint.parse(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Discord webhook configuration is invalid")
                    .hasMessageNotContaining(TOKEN);
        }
    }
}
