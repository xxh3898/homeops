package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.security.HomeOpsSecurityProperties.AuthenticationMode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class TailscaleIdentityFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void should_authenticateAdmin_when_tailscaleIdentityIsAllowed()
            throws Exception {
        var properties = new HomeOpsSecurityProperties(
                AuthenticationMode.TAILSCALE,
                List.of("owner@example.invalid"));
        var filter = new TailscaleIdentityFilter(
                properties,
                new HttpSessionSecurityContextRepository());
        var request = new MockHttpServletRequest("GET", "/api/v1/system/summary");
        request.addHeader(
                TailscaleIdentityFilter.IDENTITY_HEADER,
                "OWNER@example.invalid");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
        assertThat(SecurityContextHolder.getContext()
                .getAuthentication()
                .getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void should_leaveRequestAnonymous_when_tailscaleIdentityIsNotAllowed()
            throws Exception {
        var properties = new HomeOpsSecurityProperties(
                AuthenticationMode.TAILSCALE,
                List.of("owner@example.invalid"));
        var filter = new TailscaleIdentityFilter(
                properties,
                new HttpSessionSecurityContextRepository());
        var request = new MockHttpServletRequest("GET", "/api/v1/system/summary");
        request.addHeader(
                TailscaleIdentityFilter.IDENTITY_HEADER,
                "other@example.invalid");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void should_clearExistingSessionAuthentication_when_identityHeaderIsMissing()
            throws Exception {
        var properties = new HomeOpsSecurityProperties(
                AuthenticationMode.TAILSCALE,
                List.of("owner@example.invalid"));
        var filter = new TailscaleIdentityFilter(
                properties,
                new HttpSessionSecurityContextRepository());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "owner@example.invalid",
                        "N/A",
                        List.of()));
        var request = new MockHttpServletRequest(
                "GET",
                "/api/v1/system/summary");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void should_skipBrowserAuthentication_when_requestTargetsAgentEndpoint()
            throws Exception {
        var properties = new HomeOpsSecurityProperties(
                AuthenticationMode.DEV,
                List.of());
        var filter = new TailscaleIdentityFilter(
                properties,
                new HttpSessionSecurityContextRepository());
        var request = new MockHttpServletRequest(
                "POST",
                "/api/v1/internal/agent/snapshots");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }
}
