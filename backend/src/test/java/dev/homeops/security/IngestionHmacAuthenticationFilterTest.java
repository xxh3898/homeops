package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.ingestion.config.HomeOpsIngestionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class IngestionHmacAuthenticationFilterTest {
    private static final String SECRET = "a".repeat(64);

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void should_authenticateAndPreserveBody_when_signatureIsValid() throws Exception {
        byte[] body = "{\"eventKey\":\"deploy-1\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Instant.now().toString();
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
    void should_reject_when_secretIsNotConfigured() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("").doFilter(request(new byte[0], Instant.now().toString(), "0".repeat(64)), response,
                (request, result) -> { throw new AssertionError("chain must not run"); });
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void should_reject_when_signatureIsExpired() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Instant.now().minus(Duration.ofMinutes(6)).toString();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(SECRET).doFilter(request(body, timestamp, signature(timestamp, body)), response,
                (request, result) -> { throw new AssertionError("chain must not run"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static IngestionHmacAuthenticationFilter filter(String secret) {
        return new IngestionHmacAuthenticationFilter(new HomeOpsIngestionProperties(secret,
                Duration.ofMinutes(5), Duration.ofMinutes(1)));
    }

    private static MockHttpServletRequest request(byte[] body, String timestamp, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ingestion/deployments");
        request.setContent(body);
        request.addHeader(IngestionHmacAuthenticationFilter.TIMESTAMP_HEADER, timestamp);
        request.addHeader(IngestionHmacAuthenticationFilter.SIGNATURE_HEADER, signature);
        return request;
    }

    private static String signature(String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
