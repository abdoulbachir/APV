package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.DispatcherType;
import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.identity.internal.AccountRepository;
import org.apvgakasinay.identity.internal.IdentityProperties;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.identity.internal.Throttle;
import org.apvgakasinay.identity.internal.IdentityClock;
import org.apvgakasinay.shared.ErrorWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagerFactories;
import org.springframework.security.authorization.AuthorizationManagerFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The security filter chain. The order of checks of the contract is built from four parts:
 *
 *   BeforeCsrfFilter   steps 1 to 3, and the absolute deadline
 *   CsrfFilter         step 4, Spring Security's own, with the token stored in the session
 *   AfterCsrfFilter    steps 5 and 6
 *   the controller     steps 7 and 8
 *
 * Spring Security's authorization rules stay in place behind that as a second barrier: deny
 * by default, and its multi-factor support requires both factors for staff (ADR 0002, probe 1).
 */
@Configuration
@EnableWebSecurity
class IdentitySecurityConfiguration {

    /** The one message of every 403 csrf_invalid, whichever part of step 4 gives it. */
    static final String CSRF_REFUSAL = "Missing or invalid CSRF token";

    private static boolean has(Authentication authentication, String authority) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static boolean isStaff(Authentication authentication) {
        return has(authentication, "ROLE_ADMIN") || has(authentication, "ROLE_SUPER_ADMIN");
    }

    /** One cookie. The secure, host-only form is mandatory outside the dev and test profiles (checked at start-up). */
    @Bean
    CookieSerializer sessionCookie(IdentityProperties properties) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName(properties.cookieName());
        cookie.setUseSecureCookie(properties.cookieSecure());
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setCookiePath("/");
        // No Domain, and no Max-Age: a session cookie whose lifetime the server decides.
        return cookie;
    }

    /** The session cookie is read through a resolver that ignores values that are not identifiers. */
    @Bean
    HttpSessionIdResolver sessionIdResolver(CookieSerializer sessionCookie) {
        return new SessionCookieResolver(sessionCookie);
    }

    /**
     * Spring Security's strict firewall, with one allowance: a percent-encoded percent sign in
     * the path. The accepted public behavior is that any malformed case code answers 404
     * case_not_found; refusing that one encoding with a bare 400 would change it. Encoded
     * slashes, path traversal sequences and everything else stay refused.
     *
     * The firewall does not judge the method. Step 1 of the request order does, for every
     * method there is: the application serves GET, HEAD and POST on the paths it declares and
     * answers anything else, TRACE included, 405 method_not_allowed with Allow, or 404.
     */
    @Bean
    HttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedPercent(true);
        firewall.setUnsafeAllowAnyHttpMethod(true);
        return firewall;
    }

    /**
     * What the firewall refuses (a path with a semicolon, an encoded slash, a doubled slash, a
     * traversal sequence) is answered in the contract's shape, not with the framework's error body.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return (request, response, rejected) -> ErrorWriter.write(request, response, 400, "validation_error", "Malformed request");
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
    SecurityFilterChain apiFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokens, SecurityContextRepository contexts,
                                       ObjectProvider<RequestMappingHandlerMapping> mappings, IdentityProperties properties,
                                       IdentityClock clock, Throttle throttle, AccountRepository accounts, AuditService audit) throws Exception {
        AuthorizationManagerFactory<RequestAuthorizationContext> factors = AuthorizationManagerFactories
                .<RequestAuthorizationContext>multiFactor()
                .when(IdentitySecurityConfiguration::isStaff)
                .requireFactors(Sessions.PASSWORD_FACTOR, Sessions.TOTP_FACTOR)
                .build();
        AuthorizationManager<RequestAuthorizationContext> fullySignedIn = factors.authenticated();
        AuthorizationManager<RequestAuthorizationContext> awaitingSecondFactor = (authentication, context) -> {
            Authentication current = authentication.get();
            return new AuthorizationDecision(isStaff(current) && has(current, Sessions.PASSWORD_FACTOR) && !has(current, Sessions.TOTP_FACTOR));
        };

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new CsrfTokenHandler()))
                .securityContext(context -> context.securityContextRepository(contexts))
                .addFilterBefore(new BeforeCsrfFilter(new Routes(mappings), properties, clock, csrfTokens), CsrfFilter.class)
                .addFilterAfter(new AfterCsrfFilter(throttle, accounts, properties, audit, clock), CsrfFilter.class)
                .headers(headers -> headers
                        // Caching is set per path by RequestIdFilter: no-cache for public reads, no-store for identity.
                        .cacheControl(cache -> cache.disable())
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/site", "/api/stats", "/api/cases", "/api/cases/**", "/media/public/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/site", "/api/stats", "/api/cases", "/api/cases/**", "/media/public/**").permitAll()
                        .requestMatchers("/api/auth/csrf", "/api/auth/options", "/api/auth/register", "/api/auth/login",
                                "/api/auth/logout", "/api/auth/set-password").permitAll()
                        .requestMatchers("/api/auth/mfa/verify", "/api/auth/mfa/enroll", "/api/auth/mfa/confirm").access(awaitingSecondFactor)
                        .requestMatchers("/api/me", "/api/auth/change-password", "/api/auth/mfa/recovery-codes").access(fullySignedIn)
                        // Deny by default: a path with no rule above is refused.
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        // Never a redirect, never HTML.
                        .authenticationEntryPoint((request, response, failure) ->
                                ErrorWriter.write(request, response, 401, "unauthenticated", "Sign-in required"))
                        .accessDeniedHandler((request, response, denied) -> {
                            if (denied instanceof CsrfException) {
                                ErrorWriter.write(request, response, 403, "csrf_invalid", CSRF_REFUSAL);
                            } else {
                                ErrorWriter.write(request, response, 403, "forbidden", "Not allowed");
                            }
                        }));
        return http.build();
    }
}
