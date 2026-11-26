package org.apvgakasinay.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.web.http.DefaultCookieSerializer;

class SessionCookieResolverTest {

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** R3 of the third independent round: only values that could be identifiers are ever looked up. */
    @Test
    void a9_10_onlyValuesThatCouldBeIdentifiersAreResolved() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("apv_session");
        SessionCookieResolver resolver = new SessionCookieResolver(serializer);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("apv_session", "AA=="),
                new Cookie("apv_session", encoded(first)),
                new Cookie("apv_session", encoded(first.substring(0, 35) + "\u0000")),
                new Cookie("apv_session", encoded(first.toUpperCase())),
                new Cookie("apv_session", encoded(first + "x")),
                new Cookie("apv_session", "%%%"),
                new Cookie("apv_session", ""),
                new Cookie("other", encoded(UUID.randomUUID().toString())),
                new Cookie("apv_session", encoded(second)));

        assertThat(resolver.resolveSessionIds(request)).containsExactly(first, second);
        assertThat(resolver.resolveSessionIds(new MockHttpServletRequest())).isEmpty();
    }
}
