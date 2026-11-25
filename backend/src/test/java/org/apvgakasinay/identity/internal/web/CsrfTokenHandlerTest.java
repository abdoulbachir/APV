package org.apvgakasinay.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** Scenarios 4.2 and 4.3: whatever is sent as a token is either the token or not a token; it is never an error. */
class CsrfTokenHandlerTest {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final CsrfTokenHandler handler = new CsrfTokenHandler();
    private final CsrfToken stored = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", java.util.UUID.randomUUID().toString());

    /** The masked form the client is given, exactly as the token endpoint reads it. */
    private String masked() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.handle(request, new MockHttpServletResponse(), () -> stored);
        return ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
    }

    private String resolve(String sent) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        if (sent != null) {
            request.addHeader("X-CSRF-TOKEN", sent);
        }
        return handler.resolveCsrfTokenValue(request, stored);
    }

    @Test
    void a4_2_theGenuineMaskedTokenResolvesToTheStoredOne() {
        String first = masked();
        String second = masked();
        assertThat(first).isNotEqualTo(second).isNotEqualTo(stored.getToken());
        assertThat(resolve(first)).isEqualTo(stored.getToken());
        assertThat(resolve(second)).isEqualTo(stored.getToken());
    }

    @Test
    void a4_3_noValueOfValidShapeIsAnErrorAndNoneResolvesToTheStoredToken() {
        SecureRandom random = new SecureRandom();
        String genuine = masked();
        AtomicReference<RuntimeException> thrown = new AtomicReference<>();
        int matched = 0;
        for (int i = 0; i < 5000; i++) {
            char[] sent = genuine.toCharArray();
            if (i % 2 == 0) {
                // The genuine token with one to four characters replaced by different ones.
                for (int k = 1 + random.nextInt(4); k > 0; k--) {
                    int at = random.nextInt(sent.length);
                    char other;
                    do {
                        other = ALPHABET.charAt(random.nextInt(64));
                    } while (other == genuine.charAt(at));
                    sent[at] = other;
                }
            } else {
                for (int at = 0; at < sent.length; at++) {
                    sent[at] = ALPHABET.charAt(random.nextInt(64));
                }
            }
            try {
                if (stored.getToken().equals(resolve(new String(sent)))) {
                    matched++;
                }
            } catch (RuntimeException failure) {
                thrown.compareAndSet(null, failure);
            }
        }
        // The values themselves stay out of the failure message.
        assertThat(thrown.get() == null).as("a token of valid shape made the handler throw").isTrue();
        assertThat(matched).isZero();
    }

    @Test
    void a4_2_otherShapesAreNotErrorsEither() {
        for (String sent : new String[] {null, "", "x", "a-made-up-token-that-is-long-enough", "!!!!", "=".repeat(96), "é".repeat(40),
                masked() + "A", masked().substring(1), "A".repeat(4096)}) {
            assertThat(resolve(sent)).isNotEqualTo(stored.getToken());
        }
    }
}
