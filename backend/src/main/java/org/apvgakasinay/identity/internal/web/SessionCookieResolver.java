package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

/**
 * Reads the session identifier from the session cookie, and ignores a value that is not an
 * identifier this server could have issued.
 *
 * Identifiers are random UUIDs in their usual lower-case text form. Anything else names no
 * session, so it is not looked up: a value holding a null byte, for one, is something the
 * database refuses to compare at all, and the refusal was an error on every path. Such a
 * cookie is now exactly a request with no session cookie.
 */
final class SessionCookieResolver implements HttpSessionIdResolver {

    private static final Pattern IDENTIFIER = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final CookieHttpSessionIdResolver cookies = new CookieHttpSessionIdResolver();

    SessionCookieResolver(CookieSerializer serializer) {
        cookies.setCookieSerializer(serializer);
    }

    @Override
    public List<String> resolveSessionIds(HttpServletRequest request) {
        return cookies.resolveSessionIds(request).stream()
                .filter(value -> value != null && IDENTIFIER.matcher(value).matches())
                .toList();
    }

    @Override
    public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
        cookies.setSessionId(request, response, sessionId);
    }

    @Override
    public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        cookies.expireSession(request, response);
    }
}
