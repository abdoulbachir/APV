package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Scenarios 11.5 and 11.6: what a confirmed gift shows of its donor, and showing less of it later (contract 8.4). */
class ListingIT extends DonationIntegration {

    private Browser.Answer restrict(Staff owner, String id, String listing) {
        return owner.browser().post("/api/admin/donations/" + id + "/listing", body(map("listing", listing)));
    }

    @Test
    void d11_5_aConfirmedGiftListsOnlyWhatTheDonorAgreedToShow() {
        Map<String, Object> report = reportOf("HELP-904", 5000, zelle());
        report.put("payerName", "Quagga Payer");
        report.put("paymentReference", "ZEBRA-REF-1");
        report.put("contact", "okapi@example.test");
        report.put("display", map("mode", "named", "publicName", "Tapir Public"));
        Reported gift = reported(post(visitor(), "/api/donations", body(report), key()));
        Staff owner = owner();
        confirm(owner, gift, "HELP-904", 5000);

        JsonNode donors = browser().get("/api/cases/HELP-904/donors").json();
        assertThat(donors.get("totalItems").asInt()).isEqualTo(1);
        JsonNode donor = donors.at("/items/0");
        // A name, an amount, a date, and no picture: a guest has none, and a picture is a separate consent.
        assertThat(donor.get("name").asText()).isEqualTo("Tapir Public");
        assertThat(donor.get("amount").asLong()).isEqualTo(5000);
        assertThat(donor.get("avatarUrl").isNull()).isTrue();
        assertThat(donor.get("id").asText()).startsWith("lst_");
        String everythingPublic = donors + publicCase("HELP-904").toString() + browser().get("/api/cases/HELP-904/ledger").body();
        assertThat(everythingPublic).doesNotContain("Quagga").doesNotContain("ZEBRA").doesNotContain("okapi")
                .doesNotContain(gift.reference()).doesNotContain(giftId(gift.reference())).doesNotContain("zelle");
    }

    @Test
    void d11_6_aListingCanBeRestrictedToShowLessAndNeverMore() {
        Map<String, Object> report = reportOf("HELP-904", 5000, zelle());
        report.put("display", map("mode", "named", "publicName", "Tapir Public"));
        Reported gift = reported(post(visitor(), "/api/donations", body(report), key()));
        Staff owner = owner();
        String id = confirm(owner, gift, "HELP-904", 5000).get("id").asText();
        long received = publicCase("HELP-904").at("/case/received").asLong();
        long entries = count("select count(*) from ledger_entry");

        // The same as now is not less.
        assertError(restrict(owner, id, "named"), 400, "validation_error");
        Browser.Answer anonymous = restrict(owner, id, "anonymous");
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(200);
        assertValid("DonationDetail", anonymous);
        assertThat(anonymous.json().at("/decision/listing").asText()).isEqualTo("anonymous");
        assertThat(anonymous.json().at("/display/mode").asText()).isEqualTo("anonymous");
        assertThat(anonymous.json().at("/display/publicName").isNull()).isTrue();
        // From the next request the name is not shown, and it is gone from where it was kept to be shown.
        JsonNode publicCase = publicCase("HELP-904");
        assertThat(publicCase.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(publicCase.toString()).doesNotContain("Tapir");
        assertThat(count("select count(*) from donation_listing where donor_name is not null and public_id like 'lst_%'")).isZero();
        assertError(restrict(owner, id, "anonymous"), 409, "donation_state_conflict");

        assertThat(restrict(owner, id, "hidden").status()).isEqualTo(200);
        assertThat(publicCase("HELP-904").at("/counts/donors").asInt()).isZero();
        // It can only show less: hidden stays hidden.
        assertError(restrict(owner, id, "anonymous"), 409, "donation_state_conflict");
        assertError(restrict(owner, id, "hidden"), 409, "donation_state_conflict");

        // No amount, total or ledger entry changed, and each change is in the audit log with what it was and became.
        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isEqualTo(received);
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.listing_restricted' and details ->> 'from' = 'named'"
                + " and details ->> 'to' = 'anonymous'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.listing_restricted' and details ->> 'from' = 'anonymous'"
                + " and details ->> 'to' = 'hidden'")).isEqualTo(1);

        // A gift that is not confirmed has no listing to restrict.
        Reported waiting = report("HELP-904", 100, currentDetails("zelle"));
        assertError(restrict(owner, giftId(waiting.reference()), "hidden"), 409, "donation_state_conflict");
    }
}
