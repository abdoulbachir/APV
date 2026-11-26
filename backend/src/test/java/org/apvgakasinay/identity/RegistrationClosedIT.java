package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Scenarios 1.10 and 14.30: the server with public sign-up closed, as it is in production. */
@TestPropertySource(properties = "apv.identity.registration-open=false")
class RegistrationClosedIT extends IdentityIntegration {

    @Test
    void a1_10_closedSignUpIsAnnouncedAndRefused() {
        Browser browser = browser();
        Browser.Answer options = browser.get("/api/auth/options");
        assertThat(options.status()).isEqualTo(200);
        assertValid("AuthOptions", options);
        assertThat(options.json().get("registrationOpen").asBoolean()).isFalse();
        assertThat(options.header("Cache-Control")).isEqualTo("no-store");

        assertError(register(browser, "sample.donor@example.org", PASSWORD), 403, "registration_closed");
        assertThat(count("select count(*) from account")).isZero();
        assertThat(count("select count(*) from audit_event where event_type = 'account.sign_up_closed'")).isEqualTo(1);
    }

    @Test
    void a14_30_theClosedSwitchIsDecidedBeforeTheBodyIsValidated() {
        Browser browser = browser();
        browser.csrf();
        assertError(browser.post("/api/auth/register", "{\"nonsense\":true}"), 403, "registration_closed");
        // Without a token it is the token that is refused first.
        assertError(browser.post("/api/auth/register", "{}", null), 403, "csrf_invalid");
    }

    @Test
    void staffStillSignInWhenSignUpIsClosed() {
        Staff admin = staff("sample.admin@example.org", "admin");
        assertThat(admin.browser().get("/api/me").json().get("role").asText()).isEqualTo("admin");
    }
}
