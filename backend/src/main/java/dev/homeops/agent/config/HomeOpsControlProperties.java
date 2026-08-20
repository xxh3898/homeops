package dev.homeops.agent.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.control")
public final class HomeOpsControlProperties {

    private static final int MAXIMUM_ALLOWLIST_CHARACTERS = 2_048;
    private static final int MAXIMUM_PROJECTS = 32;
    private static final Pattern PROJECT_NAME =
            Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");

    private final Set<String> allowedProjects;

    public HomeOpsControlProperties(String allowedProjects) {
        this.allowedProjects = parse(allowedProjects);
    }

    public boolean allows(String composeProject) {
        return composeProject != null && allowedProjects.contains(composeProject);
    }

    public int allowedProjectCount() {
        return allowedProjects.size();
    }

    @Override
    public String toString() {
        return "HomeOpsControlProperties[allowedProjectCount=" + allowedProjects.size() + "]";
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
}
