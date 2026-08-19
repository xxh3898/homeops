package dev.homeops.notification.discord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DiscordWebhookEndpoint {
    private static final Pattern PATH = Pattern.compile(
            "^/api/webhooks/[0-9]{1,32}/[A-Za-z0-9._-]{32,256}$");

    private final URI executionUri;

    private DiscordWebhookEndpoint(URI executionUri) {
        this.executionUri = executionUri;
    }

    public static DiscordWebhookEndpoint parse(String value) {
        try {
            URI configured = new URI(value);
            String scheme = configured.getScheme();
            String host = configured.getHost();
            if (!"https".equalsIgnoreCase(scheme)
                    || host == null
                    || !"discord.com".equals(host.toLowerCase(Locale.ROOT))
                    || configured.getPort() != -1
                    || configured.getRawUserInfo() != null
                    || configured.getRawQuery() != null
                    || configured.getRawFragment() != null
                    || !PATH.matcher(configured.getRawPath()).matches()) {
                throw invalid();
            }
            URI execution = new URI(
                    "https", null, "discord.com", -1,
                    configured.getRawPath(), "wait=true", null);
            return new DiscordWebhookEndpoint(execution);
        } catch (URISyntaxException | NullPointerException exception) {
            throw invalid();
        }
    }

    URI executionUri() {
        return executionUri;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Discord webhook configuration is invalid");
    }

    @Override
    public String toString() {
        return "DiscordWebhookEndpoint[redacted]";
    }
}
