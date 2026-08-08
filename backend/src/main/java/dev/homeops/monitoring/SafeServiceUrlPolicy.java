package dev.homeops.monitoring;

import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SafeServiceUrlPolicy {
    private final Set<String> allowedOrigins;

    public SafeServiceUrlPolicy(HomeOpsMonitoringProperties properties) {
        this.allowedOrigins = properties.allowedOrigins().stream()
                .filter(origin -> !origin.isBlank())
                .map(SafeServiceUrlPolicy::normalizeOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String value) {
        URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (IllegalArgumentException exception) {
            throw new UnsafeServiceUrlException();
        }
        String origin;
        try {
            origin = targetOrigin(uri);
        } catch (IllegalArgumentException exception) {
            throw new UnsafeServiceUrlException();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || !allowedOrigins.contains(origin)) {
            throw new UnsafeServiceUrlException();
        }
    }

    private static String normalizeOrigin(String value) {
        try {
            return normalizeOrigin(URI.create(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Monitoring allowlist contains an invalid origin", exception);
        }
    }

    private static String normalizeOrigin(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Monitoring allowlist entries must be HTTPS origins");
        }
        return targetOrigin(uri);
    }

    private static String targetOrigin(URI uri) {
        int port = uri.getPort();
        if (uri.getHost() == null || uri.getHost().contains(":")
                || port < -1 || port == 0 || port > 65535) {
            throw new IllegalArgumentException("Monitoring allowlist contains an invalid port");
        }
        return "https://" + uri.getHost().toLowerCase()
                + (port == -1 || port == 443 ? "" : ":" + port);
    }

    public static class UnsafeServiceUrlException extends RuntimeException {
        public UnsafeServiceUrlException() {
            super("Service URL origin is not present in the monitoring allowlist");
        }
    }
}
