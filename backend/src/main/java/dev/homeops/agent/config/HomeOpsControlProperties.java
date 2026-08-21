package dev.homeops.agent.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.control")
public final class HomeOpsControlProperties {

    private static final int MAXIMUM_ALLOWLIST_CHARACTERS = 2_048;
    private static final int MAXIMUM_PROJECTS = 32;
    private static final int MAXIMUM_PUBLIC_ORIGIN_CHARACTERS = 512;
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final Pattern PROJECT_NAME =
            Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");
    private static final Pattern PUBLIC_ORIGIN =
            Pattern.compile("^https://([A-Za-z0-9.-]+)(?::([1-9][0-9]{0,4}))?$");
    private static final Pattern HOST_LABEL =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

    private final Set<String> allowedProjects;
    private final OriginAuthority publicOrigin;

    public HomeOpsControlProperties(String allowedProjects, String publicOrigin) {
        this.allowedProjects = parse(allowedProjects);
        this.publicOrigin = parseConfiguredPublicOrigin(publicOrigin);
    }

    public boolean allows(String composeProject) {
        return composeProject != null && allowedProjects.contains(composeProject);
    }

    public int allowedProjectCount() {
        return allowedProjects.size();
    }

    public boolean matchesPublicOrigin(String candidate) {
        if (publicOrigin == null) {
            return false;
        }
        try {
            return publicOrigin.equals(parseExactPublicOrigin(candidate));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean publicOriginConfigured() {
        return publicOrigin != null;
    }

    @Override
    public String toString() {
        return "HomeOpsControlProperties[allowedProjectCount=" + allowedProjects.size()
                + ", publicOriginConfigured=" + (publicOrigin != null) + "]";
    }

    private static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        if (raw.length() > MAXIMUM_ALLOWLIST_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Control project allowlist exceeds the supported size bound");
        }
        String[] entries = raw.split(",", -1);
        if (entries.length > MAXIMUM_PROJECTS) {
            throw new IllegalArgumentException(
                    "Control project allowlist exceeds the supported entry bound");
        }
        Set<String> projects = new LinkedHashSet<>(entries.length);
        for (String entry : entries) {
            String project = entry.trim();
            if (project.isEmpty()) {
                throw new IllegalArgumentException(
                        "Control project allowlist contains an empty entry");
            }
            if (!PROJECT_NAME.matcher(project).matches()) {
                throw new IllegalArgumentException(
                        "Control project allowlist contains an invalid entry");
            }
            if (!projects.add(project)) {
                throw new IllegalArgumentException(
                        "Control project allowlist contains a duplicate entry");
            }
        }
        return Collections.unmodifiableSet(projects);
    }

    private static OriginAuthority parseConfiguredPublicOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseExactPublicOrigin(raw);
    }

    private static OriginAuthority parseExactPublicOrigin(String raw) {
        if (raw == null || raw.length() > MAXIMUM_PUBLIC_ORIGIN_CHARACTERS) {
            throw invalidPublicOrigin();
        }
        Matcher matcher = PUBLIC_ORIGIN.matcher(raw);
        if (!matcher.matches()) {
            throw invalidPublicOrigin();
        }

        String host = matcher.group(1);
        if (host.length() > 253) {
            throw invalidPublicOrigin();
        }
        for (String label : host.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw invalidPublicOrigin();
            }
        }

        int port = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
        if (port > 65_535) {
            throw invalidPublicOrigin();
        }
        if (port == HTTPS_DEFAULT_PORT) {
            port = -1;
        }
        return new OriginAuthority(host.toLowerCase(Locale.ROOT), port);
    }

    private static IllegalArgumentException invalidPublicOrigin() {
        return new IllegalArgumentException(
                "Control public origin must be an exact HTTPS authority");
    }

    private record OriginAuthority(String host, int port) { }
}
