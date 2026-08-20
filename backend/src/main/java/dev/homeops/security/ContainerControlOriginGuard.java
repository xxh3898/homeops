package dev.homeops.security;

import dev.homeops.agent.control.ContainerActionException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public final class ContainerControlOriginGuard {
    public static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    public void requireSameOrigin(HttpServletRequest request) {
        if (!"https".equals(singleHeader(request, FORWARDED_PROTO_HEADER))) {
            throw ContainerActionException.originRejected();
        }
        Authority origin = parseOrigin(singleHeader(request, HttpHeaders.ORIGIN));
        Authority host = parseHost(singleHeader(request, HttpHeaders.HOST));
        if (!origin.equals(host)) {
            throw ContainerActionException.originRejected();
        }
    }

    private static Authority parseOrigin(String value) {
        URI uri = parse(value);
        if (!"https".equals(uri.getScheme())
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw ContainerActionException.originRejected();
        }
        return authority(uri);
    }

    private static Authority parseHost(String value) {
        URI uri = parse("https://" + value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw ContainerActionException.originRejected();
        }
        return authority(uri);
    }

    private static Authority authority(URI uri) {
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            throw ContainerActionException.originRejected();
        }
        return new Authority(uri.getHost().toLowerCase(Locale.ROOT), port);
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException | NullPointerException exception) {
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

    private record Authority(String host, int port) {
    }
}
