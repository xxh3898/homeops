package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.ingestion.config.HomeOpsIngestionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class IngestionHmacAuthenticationFilterTest {
    private static final String SECRET = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final int MAXIMUM_BODY_BYTES = 32 * 1024;

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void should_authenticateAndPreserveBody_when_signatureIsValid() throws Exception {
        byte[] body = "{\"eventKey\":\"deploy-1\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.toString();
        MockHttpServletRequest request = request(body, timestamp, signature(timestamp, body));
        MockHttpServletResponse response = new MockHttpServletResponse();
        var filter = filter(SECRET);

        filter.doFilter(request, response, (wrapped, ignored) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(body);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting(Object::toString).containsExactly("ROLE_INGESTION");
        });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void should_authenticateSignalEndpoint_when_signatureIsValid() throws Exception {
        byte[] body = "{\"eventKey\":\"signal-1\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.toString();
        MockHttpServletRequest request = request(body, timestamp, signature(timestamp, body));
        request.setRequestURI("/api/v1/internal/ingestion/signals");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();

        filter(SECRET).doFilter(request, response, (wrapped, ignored) -> chainCalls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    @Test
    void should_reject_when_secretIsNotConfigured() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("").doFilter(request(new byte[0], NOW.toString(), "0".repeat(64)), response,
                (request, result) -> { throw new AssertionError("chain must not run"); });
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void should_reject_when_signatureIsExpired() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.minus(Duration.ofMinutes(5)).minusNanos(1).toString();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(SECRET).doFilter(request(body, timestamp, signature(timestamp, body)), response,
                (request, result) -> { throw new AssertionError("chain must not run"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void should_authenticate_when_timestampIsAtAllowedFutureSkewBoundary() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.plus(Duration.ofMinutes(1)).toString();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SECRET).doFilter(request(body, timestamp, signature(timestamp, body)), response,
                (request, result) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void should_reject_when_timestampExceedsAllowedFutureSkew() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.plus(Duration.ofMinutes(1)).plusNanos(1).toString();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SECRET).doFilter(request(body, timestamp, signature(timestamp, body)), response,
                (request, result) -> { throw new AssertionError("chain must not run"); });

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void should_rejectWithoutCallingChain_when_signatureIsWrong() throws Exception {
        byte[] body = "{\"eventKey\":\"synthetic-deployment\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.toString();
        String validSignature = signature(timestamp, body);
        String wrongSignature = validSignature.substring(0, validSignature.length() - 1)
                + (validSignature.endsWith("0") ? "1" : "0");

        MockHttpServletResponse response = rejectWithoutCallingChain(body, timestamp, wrongSignature);

        assertThat(response.getErrorMessage()).isEqualTo("Invalid ingestion authentication");
        assertThat(response.getContentAsString()).doesNotContain("synthetic-deployment", wrongSignature);
    }

    @Test
    void should_rejectWithoutCallingChain_when_signatureIsMissing() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse response = rejectWithoutCallingChain(body, NOW.toString(), null);

        assertThat(response.getErrorMessage()).isEqualTo("Invalid ingestion authentication");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "000000000000000000000000000000000000000000000000000000000000000",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    })
    void should_rejectWithoutCallingChain_when_signatureIsMalformed(String malformedSignature) throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse response = rejectWithoutCallingChain(body, NOW.toString(), malformedSignature);

        assertThat(response.getErrorMessage()).isEqualTo("Invalid ingestion authentication");
    }

    @Test
    void should_authenticateAndPreserveBody_when_bodyIsAtMaximumSize() throws Exception {
        byte[] body = "x".repeat(MAXIMUM_BODY_BYTES).getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.toString();
        MockHttpServletRequest request = request(body, timestamp, signature(timestamp, body));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();

        filter(SECRET).doFilter(request, response, (wrapped, ignored) -> {
            chainCalls.incrementAndGet();
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(body);
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls).hasValue(1);
    }

    @Test
    void should_rejectWithoutCallingChain_when_bodyExceedsMaximumSize() throws Exception {
        byte[] body = "x".repeat(MAXIMUM_BODY_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        String timestamp = NOW.toString();

        MockHttpServletResponse response = rejectWithoutCallingChain(body, timestamp, signature(timestamp, body));

        assertThat(response.getErrorMessage()).isEqualTo("Invalid ingestion authentication");
    }

    private static IngestionHmacAuthenticationFilter filter(String secret) {
        return new IngestionHmacAuthenticationFilter(new HomeOpsIngestionProperties(secret,
                Duration.ofMinutes(5), Duration.ofMinutes(1)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(byte[] body, String timestamp, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ingestion/deployments");
        request.setContent(body);
        request.addHeader(IngestionHmacAuthenticationFilter.TIMESTAMP_HEADER, timestamp);
        if (signature != null) {
            request.addHeader(IngestionHmacAuthenticationFilter.SIGNATURE_HEADER, signature);
        }
        return request;
    }

    private static MockHttpServletResponse rejectWithoutCallingChain(byte[] body, String timestamp,
            String providedSignature) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();

        filter(SECRET).doFilter(request(body, timestamp, providedSignature), response,
                (request, result) -> chainCalls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalls).hasValue(0);
        return response;
    }

    private static String signature(String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
