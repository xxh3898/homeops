package dev.homeops.notification;

import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ContainerNotificationIdentity {
    private static final String LOGICAL_DOMAIN = "homeops-container-logical-v1";
    private static final String INSTANCE_DOMAIN = "homeops-container-instance-v1";

    private ContainerNotificationIdentity() { }

    static Identity from(ContainerSnapshot container) {
        String composeProject = canonicalProject(container.composeProject());
        String logicalMaterial = component(LOGICAL_DOMAIN)
                + component(composeProject == null ? "" : composeProject)
                + component(container.name());
        return new Identity(
                sha256(logicalMaterial),
                container.name(),
                composeProject,
                sha256(component(INSTANCE_DOMAIN) + component(container.id())));
    }

    private static String canonicalProject(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }

    record Identity(
            String logicalHash,
            String displayName,
            String composeProject,
            String instanceFingerprint) { }
}
