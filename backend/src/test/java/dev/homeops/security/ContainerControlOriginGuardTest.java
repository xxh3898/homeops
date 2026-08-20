package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.agent.control.ContainerActionException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class ContainerControlOriginGuardTest {
    private final ContainerControlOriginGuard guard = new ContainerControlOriginGuard();

    @Test
    void should_acceptExactHttpsOrigin_when_hostAndForwardedProtoMatch() {
        MockHttpServletRequest request = request(
                "https://homeops.example.test:8443",
                "homeops.example.test:8443",
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
        "https://other.example.test"
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
                request("https://homeops.example.test", null, "https"),
                request("https://homeops.example.test", "homeops.example.test", null),
                request("https://homeops.example.test", "homeops.example.test", "http"));
        MockHttpServletRequest duplicateOrigin = request(
                "https://homeops.example.test",
                "homeops.example.test",
                "https");
        duplicateOrigin.addHeader(HttpHeaders.ORIGIN, "https://homeops.example.test");
        requests = new java.util.ArrayList<>(requests);
        requests.add(duplicateOrigin);

        for (MockHttpServletRequest request : requests) {
            assertThatThrownBy(() -> guard.requireSameOrigin(request))
                    .isInstanceOf(ContainerActionException.class);
        }
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
