package dev.homeops.security;

import dev.homeops.security.HomeOpsSecurityProperties.AuthenticationMode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public class TailscaleIdentityFilter extends OncePerRequestFilter {

    static final String IDENTITY_HEADER = "Tailscale-User-Login";

    private final HomeOpsSecurityProperties properties;
    private final SecurityContextRepository securityContextRepository;

    public TailscaleIdentityFilter(
            HomeOpsSecurityProperties properties,
            SecurityContextRepository securityContextRepository) {
        this.properties = properties;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/v1/internal/agent/")
                || path.startsWith("/api/v1/internal/ingestion/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String principal = resolvePrincipal(request);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        if (principal != null) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    "N/A",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            context.setAuthentication(authentication);
        }
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        filterChain.doFilter(request, response);
    }

    private String resolvePrincipal(HttpServletRequest request) {
        if (properties.mode() == AuthenticationMode.DEV) {
            return "local-dev";
        }
        String login = request.getHeader(IDENTITY_HEADER);
        return properties.isAllowed(login) ? login.strip() : null;
    }
}
