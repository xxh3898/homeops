package dev.homeops.security;

import dev.homeops.agent.config.HomeOpsControlProperties;
import dev.homeops.agent.control.ContainerActionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public final class ContainerControlOriginGuard {
    public static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    private final HomeOpsControlProperties controlProperties;

    public ContainerControlOriginGuard(HomeOpsControlProperties controlProperties) {
        this.controlProperties = controlProperties;
    }

    public void requireSameOrigin(HttpServletRequest request) {
        if (!"https".equals(singleHeader(request, FORWARDED_PROTO_HEADER))) {
            throw ContainerActionException.originRejected();
        }
        if (!controlProperties.matchesPublicOrigin(singleHeader(request, HttpHeaders.ORIGIN))) {
            throw ContainerActionException.originRejected();
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw ContainerActionException.originRejected();
        }
        return values.getFirst();
    }
}
