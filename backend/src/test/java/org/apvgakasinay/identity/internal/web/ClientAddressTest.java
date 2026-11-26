package org.apvgakasinay.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Scenario 8.8: a forwarded-for header is believed only from a configured reverse proxy. */
class ClientAddressTest {

    private static MockHttpServletRequest from(String connection, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(connection);
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        return request;
    }

    @Test
    void a8_8_aForgedHeaderFromAnUntrustedConnectionIsIgnored() {
        assertThat(ClientAddress.of(from("203.0.113.9", "10.1.2.3"), List.of())).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.of(from("203.0.113.9", "10.1.2.3"), List.of("127.0.0.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void a8_8_onlyTheEntryTheTrustedProxyAddedCounts() {
        // The client can put anything on the left; the proxy appends the address it saw on the right.
        assertThat(ClientAddress.of(from("127.0.0.1", "1.1.1.1, 198.51.100.7"), List.of("127.0.0.1"))).isEqualTo("198.51.100.7");
        assertThat(ClientAddress.of(from("127.0.0.1", null), List.of("127.0.0.1"))).isEqualTo("127.0.0.1");
        assertThat(ClientAddress.of(from("127.0.0.1", "not an address <script>"), List.of("127.0.0.1"))).isEqualTo("127.0.0.1");
    }

    /** Independent review, D4: a header that holds no entry at all was answered 500. */
    @Test
    void a8_8_aForwardedHeaderWithNoEntryFallsBackToTheConnection() {
        for (String header : new String[] {",", ",,", " , ", ", ,", "198.51.100.7,", ";", ",\t,"}) {
            assertThat(ClientAddress.of(from("127.0.0.1", header), List.of("127.0.0.1"))).as("[%s]", header).isEqualTo("127.0.0.1");
        }
        assertThat(ClientAddress.of(from("127.0.0.1", ",198.51.100.7"), List.of("127.0.0.1"))).isEqualTo("198.51.100.7");
    }
}
