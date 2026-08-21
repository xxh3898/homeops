package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.agent.config.HomeOpsControlProperties;
import dev.homeops.agent.control.ContainerActionException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class ContainerControlOriginGuardTest {
    private static final String PUBLIC_ORIGIN = "https://homeops.example.test:8443";

    private final ContainerControlOriginGuard guard = new ContainerControlOriginGuard(
            new HomeOpsControlProperties("", PUBLIC_ORIGIN));

    @Test
    void should_acceptExactHttpsOrigin_when_hostAndForwardedProtoMatch() {
        MockHttpServletRequest request = request(
                PUBLIC_ORIGIN,
                "homeops.example.test:8443",
                "https");

        assertThatCode(() -> guard.requireSameOrigin(request)).doesNotThrowAnyException();
    }

    @Test
    void should_acceptPublicOriginWithExplicitPort_when_proxyHostOmitsPort() {
        MockHttpServletRequest request = request(
                PUBLIC_ORIGIN,
                "homeops.example.test",
                "https");

        assertThatCode(() -> guard.requireSameOrigin(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null",
        "http://homeops.example.test",
        "HTTPS://homeops.example.test",
        "https://user@homeops.example.test",
        "https://homeops.example.test/",
        "https://homeops.example.test/path",
        "https://homeops.example.test?query=1",
        "https://homeops.example.test#fragment",
        "https://other.example.test",
        "https://homeops.example.test:9443"
    })
    void should_rejectUnsafeOriginShape_when_originIsNotExact(String origin) {
        MockHttpServletRequest request = request(
                origin,
                "homeops.example.test",
                "https");

        assertThatThrownBy(() -> guard.requireSameOrigin(request))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test
    void should_rejectMissingDuplicateAndNonHttpsProxyHeaders() {
        List<MockHttpServletRequest> requests = List.of(
                request(null, "homeops.example.test", "https"),
                request(PUBLIC_ORIGIN, "homeops.example.test", null),
                request(PUBLIC_ORIGIN, "homeops.example.test", "http"));
        MockHttpServletRequest duplicateOrigin = request(
                PUBLIC_ORIGIN,
                "homeops.example.test",
                "https");
        duplicateOrigin.addHeader(HttpHeaders.ORIGIN, PUBLIC_ORIGIN);
        requests = new java.util.ArrayList<>(requests);
        requests.add(duplicateOrigin);

        for (MockHttpServletRequest request : requests) {
            assertThatThrownBy(() -> guard.requireSameOrigin(request))
                    .isInstanceOf(ContainerActionException.class);
        }
    }

    @Test
    void should_failClosed_when_publicOriginIsNotConfigured() {
        ContainerControlOriginGuard disabledGuard = new ContainerControlOriginGuard(
                new HomeOpsControlProperties("", ""));

        assertThatThrownBy(() -> disabledGuard.requireSameOrigin(request(
                PUBLIC_ORIGIN,
                "homeops.example.test",
                "https")))
                .isInstanceOf(ContainerActionException.class);
    }

    @Test
    void should_rejectWrongOrigin_even_when_forwardedHostSpoofsConfiguredAuthority() {
        MockHttpServletRequest request = request(
                "https://other.example.test:8443",
                "homeops.example.test",
                "https");
        request.addHeader("X-Forwarded-Host", "homeops.example.test:8443");

        assertThatThrownBy(() -> guard.requireSameOrigin(request))
                .isInstanceOf(ContainerActionException.class);
    }

    private static MockHttpServletRequest request(
            String origin,
            String host,
            String forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/containers/id/actions");
        if (origin != null) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
        }
        if (host != null) {
            request.addHeader(HttpHeaders.HOST, host);
        }
        if (forwardedProto != null) {
            request.addHeader(ContainerControlOriginGuard.FORWARDED_PROTO_HEADER, forwardedProto);
        }
        return request;
    }
}
