package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Definition;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Localized;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 1 of contract/tests/donation-acceptance.md: what a visitor reads about payment methods, and how methods change. */
class PaymentMethodsIT extends DonationIntegration {

    private Browser.Answer methods() {
        return browser().get("/api/giving/methods");
    }

    @Test
    void d1_1_activeMethodsAreListedInOrderWithTheirCurrentDetailsInBothLanguages() {
        String paypal = method("paypal", 2);
        String zelle = method("zelle", 1);

        Browser.Answer answer = methods();
        assertThat(answer.status()).isEqualTo(200);
        assertValid("PaymentMethodList", answer);
        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
        assertThat(answer.header("ETag")).isNull();
        // 9.9: the content policy of every answer is the one of milestone 1: no script from anywhere, no third party.
        assertThat(answer.header("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(answer.header("X-Content-Type-Options")).isEqualTo("nosniff");
        JsonNode listed = answer.json().get("methods");
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("id").asText()).isEqualTo("zelle");
        assertThat(listed.get(0).get("detailsId").asText()).isEqualTo(zelle);
        assertThat(listed.get(0).at("/labels/en").asText()).isEqualTo("Zelle");
        assertThat(listed.get(0).at("/instructions/fr").asText()).startsWith("Envoyez");
        assertThat(listed.get(0).at("/recipient/0/value").asText()).isEqualTo("gifts+zelle@example.test");
        assertThat(listed.get(0).at("/recipient/0/format").asText()).isEqualTo("text");
        assertThat(listed.get(1).get("id").asText()).isEqualTo("paypal");
        assertThat(listed.get(1).get("detailsId").asText()).isEqualTo(paypal);
    }

    @Test
    void d1_2_withNoActiveMethodTheListIsEmpty() {
        Browser.Answer nothing = methods();
        assertValid("PaymentMethodList", nothing);
        assertThat(nothing.json().get("methods")).isEmpty();

        // A method that exists but is not active, or was deactivated, is not offered either.
        paymentMethods.create("cashapp", "Cash App", "Cash App", 3, "test-operator");
        paymentMethods.publishDetails("cashapp", sampleDetails("$sample"), "test-operator");
        assertThat(methods().json().get("methods")).isEmpty();
        paymentMethods.activate("cashapp", null, "test-operator");
        assertThat(methods().json().get("methods")).hasSize(1);
        paymentMethods.deactivate("cashapp", "test-operator");
        assertThat(methods().json().get("methods")).isEmpty();
    }

    @Test
    void d1_3_d1_7_publishingSupersedesThePreviousVersionAtOneInstantAndOnlyTheCurrentIsReturned() {
        String first = zelle();
        int revision = paymentMethods.publishDetails("zelle", sampleDetails("new-handle@example.test"), "test-operator");
        assertThat(revision).isEqualTo(2);
        String second = currentDetails("zelle");
        assertThat(second).isNotEqualTo(first);

        JsonNode listed = methods().json().get("methods");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("detailsId").asText()).isEqualTo(second);
        assertThat(listed.get(0).at("/recipient/0/value").asText()).isEqualTo("new-handle@example.test");

        // One current version at all times: the old one was superseded at the instant the new one was published.
        assertThat(count("select count(*) from payment_method_details where method_id = 'zelle' and superseded_at is null")).isEqualTo(1);
        assertThat(count("""
                select count(*) from payment_method_details old
                  join payment_method_details new on new.method_id = old.method_id and new.revision = 2
                 where old.revision = 1 and old.superseded_at = new.published_at
                """)).isEqualTo(1);
        // A published version is never edited or deleted.
        assertThatThrownBy(() -> jdbc.sql("update payment_method_details set instructions_en = 'changed' where revision = 2").update())
                .hasMessageContaining("only superseded_at may be set");
        assertThatThrownBy(() -> jdbc.sql("delete from payment_method_details where revision = 1").update())
                .hasMessageContaining("never deleted");
        assertThatThrownBy(() -> jdbc.sql("update payment_method set id = 'renamed' where id = 'zelle'").update())
                .hasMessageContaining("never changes");
    }

    @Test
    void d1_4_theListCarriesNothingAboutAnyAccountEvenForASignedInSuperAdministrator() {
        zelle();
        Staff owner = owner();
        Browser.Answer answer = owner.browser().get("/api/giving/methods");
        assertValid("PaymentMethodList", answer);
        // The eligibility attestation and who managed the method are not part of what a visitor reads.
        assertThat(answer.body()).doesNotContain("eligibility").doesNotContain("test-operator").doesNotContain("sample.owner");
        assertThat(answer.body()).isEqualTo(methods().body());
    }

    @Test
    void d1_5_aLinkValueMustBeAnHttpsAddressWithoutCredentials() {
        paymentMethods.create("paypal", "PayPal", "PayPal", 1, "test-operator");
        Localized instructions = new Localized("Open the link.", "Ouvrez le lien.");
        Localized label = new Localized("Payment page", "Page de paiement");
        for (String refused : List.of("http://paypal.example.test/sample", "https://user:secret@paypal.example.test/sample",
                "javascript:alert(1)", "paypal.example.test/sample", "https://" + "a".repeat(200) + ".example.test/")) {
            assertThatThrownBy(() -> paymentMethods.publishDetails("paypal",
                    new Definition(instructions, List.of(new RecipientField(label, refused, "link"))), "test-operator"))
                    .as(refused).isInstanceOf(PaymentMethodAdministration.Refused.class);
        }
        assertThat(count("select count(*) from payment_method_details")).isZero();
        paymentMethods.publishDetails("paypal",
                new Definition(instructions, List.of(new RecipientField(label, "https://paypal.example.test/sample-recipient", "link"))), "test-operator");
        paymentMethods.activate("paypal", null, "test-operator");
        JsonNode field = methods().json().at("/methods/0/recipient/0");
        assertThat(field.get("format").asText()).isEqualTo("link");
        assertThat(field.get("value").asText()).isEqualTo("https://paypal.example.test/sample-recipient");
    }

    @Test
    void d1_7_aMethodNeedsDetailsBeforeItCanBeActiveAndShowsOneToSixFields() {
        paymentMethods.create("zelle", "Zelle", "Zelle", 1, "test-operator");
        assertThatThrownBy(() -> paymentMethods.activate("zelle", null, "test-operator")).isInstanceOf(PaymentMethodAdministration.Refused.class);
        RecipientField field = new RecipientField(new Localized("Send to", "Envoyer à"), "gifts@example.test", "text");
        Localized instructions = new Localized("Send it.", "Envoyez-le.");
        assertThatThrownBy(() -> paymentMethods.publishDetails("zelle", new Definition(instructions, List.of()), "test-operator"))
                .isInstanceOf(PaymentMethodAdministration.Refused.class);
        assertThatThrownBy(() -> paymentMethods.publishDetails("zelle", new Definition(instructions, java.util.Collections.nCopies(7, field)), "test-operator"))
                .isInstanceOf(PaymentMethodAdministration.Refused.class);
        // An identifier is never reused, and two methods never share a position.
        assertThatThrownBy(() -> paymentMethods.create("zelle", "Other", "Autre", 2, "test-operator")).isInstanceOf(PaymentMethodAdministration.Refused.class);
        assertThatThrownBy(() -> paymentMethods.create("paypal", "PayPal", "PayPal", 1, "test-operator")).isInstanceOf(PaymentMethodAdministration.Refused.class);
        assertThatThrownBy(() -> paymentMethods.create("Not An Id", "X", "X", 5, "test-operator")).isInstanceOf(PaymentMethodAdministration.Refused.class);
    }

    @Test
    void d1_10_everyChangeToAMethodIsAuditedWithTheOperatorAndNoNetworkRouteChangesOne() {
        zelle();
        paymentMethods.deactivate("zelle", "test-operator");
        for (String event : List.of("payment_method.created", "payment_method.details_published", "payment_method.activated", "payment_method.deactivated")) {
            assertThat(count("select count(*) from audit_event where event_type = '" + event
                    + "' and actor_operator = 'test-operator' and outcome = 'success' and details ->> 'method' = 'zelle'")).as(event).isEqualTo(1);
        }
        // Milestone 2 has no network operation that changes a method or a recipient detail (2.5).
        Staff owner = owner();
        assertError(owner.browser().post("/api/giving/methods", "{}"), 405, "method_not_allowed");
        assertError(owner.browser().post("/api/admin/payment-methods", "{}"), 404, "not_found");
        assertError(owner.browser().post("/api/giving/methods/zelle/activate", "{}"), 404, "not_found");
    }

    @Test
    void d1_11_anUnsupportedMethodIsAnswered405WithAllow() {
        Browser browser = browser();
        browser.csrf();
        Browser.Answer answer = browser.post("/api/giving/methods", "{}");
        assertError(answer, 405, "method_not_allowed");
        assertThat(answer.header("Allow")).isEqualTo("GET, HEAD");
        // HEAD is answered wherever GET is.
        assertThat(browser.send("HEAD", "/api/giving/methods", null, null, null).status()).isEqualTo(200);
    }
}
