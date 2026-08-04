package dev.homeops.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AgentProxyAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void should_authenticateAgent_when_proxyVerifiedMutualTls()
            throws Exception {
        var filter = new AgentProxyAuthenticationFilter();
        var request = new MockHttpServletRequest(
                "POST",
                "/api/v1/internal/agent/snapshots");
        request.addHeader(
                AgentProxyAuthenticationFilter.VERIFIED_HEADER,
                "SUCCESS");

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
                .containsExactly("ROLE_AGENT");
    }

    @Test
    void should_leaveRequestAnonymous_when_proxyVerificationIsMissing()
            throws Exception {
        var filter = new AgentProxyAuthenticationFilter();
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

