package dev.homeops.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    TailscaleIdentityFilter tailscaleIdentityFilter(
            HomeOpsSecurityProperties properties,
            SecurityContextRepository repository) {
        return new TailscaleIdentityFilter(properties, repository);
    }

    @Bean
    AgentProxyAuthenticationFilter agentProxyAuthenticationFilter() {
        return new AgentProxyAuthenticationFilter();
    }

    @Bean
    IngestionHmacAuthenticationFilter ingestionHmacAuthenticationFilter(
            dev.homeops.ingestion.config.HomeOpsIngestionProperties properties) {
        return new IngestionHmacAuthenticationFilter(properties);
    }

    @Bean
    NoStoreResponseFilter noStoreResponseFilter() {
        return new NoStoreResponseFilter();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository repository,
            TailscaleIdentityFilter tailscaleFilter,
            AgentProxyAuthenticationFilter agentFilter,
            IngestionHmacAuthenticationFilter ingestionFilter,
            NoStoreResponseFilter noStoreFilter) throws Exception {
        CookieCsrfTokenRepository csrfRepository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http.securityContext(context ->
                        context.securityContextRepository(repository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(
                                "/api/v1/internal/agent/**",
                                "/api/v1/internal/ingestion/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/internal/agent/**")
                        .hasRole("AGENT")
                        .requestMatchers("/api/v1/internal/ingestion/**")
                        .hasRole("INGESTION")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(agentFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(ingestionFilter, AgentProxyAuthenticationFilter.class)
                .addFilterBefore(tailscaleFilter, AgentProxyAuthenticationFilter.class)
                .addFilterAfter(noStoreFilter, AgentProxyAuthenticationFilter.class);

        return http.build();
    }
}
