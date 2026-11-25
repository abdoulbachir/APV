package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * Spring Security's masked-token handling, with one correction: a token that cannot be
 * unmasked is a wrong token, not a server error.
 *
 * The framework's handler unmasks the value the client sent and decodes the result as UTF-8.
 * A value of the right length and alphabet with altered content usually unmasks to bytes that
 * are not UTF-8, and the framework then throws instead of refusing. Here any such failure
 * yields "no token", which the CSRF filter refuses with 403 csrf_invalid like every other
 * wrong token. Nothing of the value is logged.
 */
final class CsrfTokenHandler implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        masked.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        try {
            return masked.resolveCsrfTokenValue(request, csrfToken);
        } catch (RuntimeException notAToken) {
            return null;
        }
    }
}
