package dev.homeops.security;

import dev.homeops.ingestion.config.HomeOpsIngestionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class IngestionHmacAuthenticationFilter extends OncePerRequestFilter {

    static final String TIMESTAMP_HEADER = "X-HomeOps-Ingestion-Timestamp";
    static final String SIGNATURE_HEADER = "X-HomeOps-Ingestion-Signature";
    private static final int MAXIMUM_BODY_BYTES = 32 * 1024;
    private final HomeOpsIngestionProperties properties;

    public IngestionHmacAuthenticationFilter(HomeOpsIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/internal/ingestion/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isConfigured()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Ingestion is not configured");
            return;
        }
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        Instant sentAt;
        try {
            sentAt = Instant.parse(timestamp);
        } catch (RuntimeException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid ingestion authentication");
            return;
        }
        Instant now = Instant.now();
        if (sentAt.isBefore(now.minus(properties.maximumRequestAge()))
                || sentAt.isAfter(now.plus(properties.allowedFutureSkew()))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid ingestion authentication");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
        if (body.length > MAXIMUM_BODY_BYTES || !isValidSignature(timestamp, body, signature)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid ingestion authentication");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken("hmac-ingestion", "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_INGESTION")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean isValidSignature(String timestamp, byte[] body, String provided) {
        if (timestamp == null || provided == null || !provided.matches("[0-9a-f]{64}")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.sharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] expected = mac.doFinal(body);
            return MessageDigest.isEqual(expected, hexToBytes(provided));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
