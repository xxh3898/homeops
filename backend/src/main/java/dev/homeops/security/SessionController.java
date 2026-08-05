package dev.homeops.security;

import java.security.Principal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    @GetMapping
    public SessionResponse session(
            Principal principal,
            CsrfToken csrfToken) {
        return new SessionResponse(
                principal.getName(),
                csrfToken.getHeaderName(),
                csrfToken.getToken());
    }

    public record SessionResponse(
            String principal,
            String csrfHeader,
            String csrfToken) {
    }
}

