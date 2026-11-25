package org.apvgakasinay.probe;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagerFactories;
import org.springframework.security.authorization.AuthorizationManagerFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Probe 1: the second factor is modelled with Spring Security's own multi-factor support.
 * A password sign-in grants the framework's password factor authority; the custom
 * time-based-code check grants a custom factor authority; and the framework's
 * AuthorizationManagerFactory requires both for staff.
 */
@Configuration
@EnableWebSecurity
class ProbeSecurityConfig {

    /** The framework's name for the password factor, and ours for the authenticator code. */
    static final String PASSWORD_FACTOR = FactorGrantedAuthority.PASSWORD_AUTHORITY;
    static final String TOTP_FACTOR = FactorGrantedAuthority.fromFactor("TOTP").getAuthority();

    static boolean has(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    static boolean isStaff(Authentication authentication) {
        return has(authentication, "ROLE_ADMIN") || has(authentication, "ROLE_SUPER_ADMIN");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // OWASP's minimum for Argon2id: 19 MiB, 2 iterations, 1 lane; 16-byte salt, 32-byte hash.
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        // The token lives in the server-side session. There is no CSRF cookie.
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain probeFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokens,
                                         SecurityContextRepository contexts, ProbeClock clock) throws Exception {
        // Staff need both factors; for everyone else "authenticated" means the password alone.
        AuthorizationManagerFactory<RequestAuthorizationContext> factors = AuthorizationManagerFactories
                .<RequestAuthorizationContext>multiFactor()
                .when(ProbeSecurityConfig::isStaff)
                .requireFactors(PASSWORD_FACTOR, TOTP_FACTOR)
                .build();
        AuthorizationManager<RequestAuthorizationContext> fullySignedIn = factors.authenticated();
        // A staff session that has proved the password and still owes the second factor.
        AuthorizationManager<RequestAuthorizationContext> awaitingSecondFactor = (authentication, context) -> {
            Authentication current = authentication.get();
            return new AuthorizationDecision(isStaff(current) && has(current, PASSWORD_FACTOR) && !has(current, TOTP_FACTOR));
        };

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .securityContext(context -> context.securityContextRepository(contexts))
                .addFilterBefore(new SessionDeadlineFilter(clock), CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/probe/public", "/probe/csrf", "/probe/login", "/probe/logout").permitAll()
                        .requestMatchers("/probe/mfa/verify", "/probe/mfa/enroll", "/probe/mfa/confirm").access(awaitingSecondFactor)
                        .requestMatchers("/probe/me", "/probe/change-password").access(fullySignedIn)
                        // Deny by default: a path with no rule is refused.
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> write(response, 401, "unauthenticated"))
                        .accessDeniedHandler((request, response, denied) -> {
                            if (denied instanceof CsrfException) {
                                write(response, 403, "csrf_invalid");
                                return;
                            }
                            Authentication current = SecurityContextHolder.getContext().getAuthentication();
                            if (isStaff(current) && has(current, PASSWORD_FACTOR) && !has(current, TOTP_FACTOR)) {
                                // Not a lost session: the second factor is still owed.
                                write(response, 401, "mfa_required");
                            } else {
                                write(response, 403, "forbidden");
                            }
                        }));
        return http.build();
    }

    static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\"}}");
    }
}
