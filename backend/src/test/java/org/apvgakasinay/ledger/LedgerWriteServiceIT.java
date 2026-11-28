package org.apvgakasinay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.apvgakasinay.cases.CaseResolver;
import org.apvgakasinay.cases.ResolvedCase;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.support.PostgresIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The write side of the ledger as the donations module will drive it: recording a confirmed
 * gift, correcting it with a reversal or a refund, and restricting its listing. Starts from
 * contract/fixtures/example.json, so what a write changes can be read back through the public API.
 */
class LedgerWriteServiceIT extends PostgresIntegration {

    private static final Instant WHEN = Instant.parse("2026-11-26T12:00:00Z");

    @Autowired
    private LedgerWriteService ledger;

    @Autowired
    private CaseResolver cases;

    @BeforeEach
    void load() {
        loadExample();
    }

    private long caseId(String code) {
        return cases.resolve(code).orElseThrow().id();
    }

    private JsonNode detail(String code) {
        return valid("CaseDetail", get("/api/cases/" + code)).json();
    }

    @Test
    void theCaseResolverTellsDonationsWhatItMayDo() {
        ResolvedCase fund = cases.resolve("FUND").orElseThrow();
        assertThat(fund.isFund()).isTrue();
        assertThat(fund.accepting()).isTrue();

        ResolvedCase open = cases.resolve("HELP-904").orElseThrow();
        assertThat(open.isFund()).isFalse();
        assertThat(open.accepting()).isTrue();
        assertThat(open.status()).isEqualTo("active");

        // Case-insensitive, like every public case lookup.
        assertThat(cases.resolve("help-904").orElseThrow().id()).isEqualTo(open.id());

        // Delivered: it is a real case, but a report may not be made against it.
        assertThat(cases.resolve("HELP-903").orElseThrow().accepting()).isFalse();

        // A draft, an unknown code and a malformed code are all indistinguishable: nothing.
        assertThat(cases.resolve("HELP-902")).isEmpty();
        assertThat(cases.resolve("ZZ")).isEmpty();
        assertThat(cases.resolve("not a code")).isEmpty();
        assertThat(cases.resolve(null)).isEmpty();
    }

    @Test
    void recordingADonationWritesOneEntryAndItsListing() {
        RecordedDonation recorded = ledger.recordDonation(caseId("HELP-904"), 5000, WHEN, DonorListing.named("Aminata"));
        assertThat(recorded.entryId()).isPositive();
        assertThat(recorded.entryPublicId()).startsWith("led_");
        assertThat(recorded.listingPublicId()).startsWith("lst_");

        JsonNode detail = detail("HELP-904");
        assertThat(detail.at("/case/received").asLong()).isEqualTo(5000);
        assertThat(detail.at("/financials/externalDonations").asLong()).isEqualTo(5000);
        assertThat(detail.at("/counts/ledger").asInt()).isEqualTo(1);
        assertThat(detail.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(detail.at("/donors/0/name").asText()).isEqualTo("Aminata");
        assertThat(detail.at("/donors/0/amount").asLong()).isEqualTo(5000);
        // Exactly one donation entry carries the recorded id, and the listing points at it.
        assertThat(jdbc.sql("select public_id from ledger_entry where id = :id")
                .param("id", recorded.entryId()).query(String.class).single()).isEqualTo(recorded.entryPublicId());
    }

    @Test
    void anonymousAndHiddenGiftsAreCountedButListedDifferently() {
        ledger.recordDonation(caseId("HELP-904"), 1000, WHEN, DonorListing.anonymous());
        ledger.recordDonation(caseId("HELP-904"), 2000, WHEN, DonorListing.hidden());

        JsonNode detail = detail("HELP-904");
        // Both are in the total; only the anonymous one is listed, and with no name.
        assertThat(detail.at("/case/received").asLong()).isEqualTo(3000);
        assertThat(detail.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(detail.at("/donors/0/name").isNull() || detail.at("/donors/0/name").asText().isEmpty()).isTrue();
        assertThat(detail.at("/donors/0/amount").asLong()).isEqualTo(1000);
    }

    @Test
    void aReversalTakesAwayAndNeverMoreThanWhatStands() {
        RecordedDonation gift = ledger.recordDonation(caseId("HELP-904"), 5000, WHEN, DonorListing.named("Aminata"));

        RecordedCorrection reversal = ledger.recordCorrection(gift.entryId(), CorrectionKind.DONATION_REVERSAL, 2000, WHEN);
        assertThat(reversal.entryPublicId()).startsWith("led_");
        assertThat(reversal.remainingCents()).isEqualTo(3000);
        assertThat(ledger.remaining(gift.entryId())).isEqualTo(3000);

        JsonNode afterReversal = detail("HELP-904");
        assertThat(afterReversal.at("/case/received").asLong()).isEqualTo(3000);
        assertThat(afterReversal.at("/donors/0/amount").asLong()).isEqualTo(3000);

        // More than what still stands is refused, and nothing is written.
        assertThatThrownBy(() -> ledger.recordCorrection(gift.entryId(), CorrectionKind.REFUND, 3001, WHEN))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("correction_exceeds_remaining");
                });
        assertThat(ledger.remaining(gift.entryId())).isEqualTo(3000);

        // A real refund for the rest leaves nothing standing; the donor drops off the list.
        RecordedCorrection refund = ledger.recordCorrection(gift.entryId(), CorrectionKind.REFUND, 3000, WHEN);
        assertThat(refund.remainingCents()).isZero();
        JsonNode afterRefund = detail("HELP-904");
        assertThat(afterRefund.at("/case/received").asLong()).isZero();
        assertThat(afterRefund.at("/counts/donors").asInt()).isZero();
        // Both corrections stay in the ledger with the gift.
        assertThat(afterRefund.at("/counts/ledger").asInt()).isEqualTo(3);
    }

    @Test
    void aListingCanBeRestrictedToShowLessButNeverMore() {
        RecordedDonation gift = ledger.recordDonation(caseId("HELP-904"), 5000, WHEN, DonorListing.named("Aminata"));
        assertThat(detail("HELP-904").at("/donors/0/name").asText()).isEqualTo("Aminata");

        ledger.restrictListing(gift.entryId(), ListingVisibility.ANONYMOUS);
        JsonNode anonymous = detail("HELP-904");
        assertThat(anonymous.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(anonymous.at("/donors/0/name").isNull() || anonymous.at("/donors/0/name").asText().isEmpty()).isTrue();
        // The name is gone from the row, not merely hidden by the query.
        assertThat(jdbc.sql("select donor_name from donation_listing where ledger_entry_id = :id")
                .param("id", gift.entryId()).query(String.class).optional()).isEmpty();

        ledger.restrictListing(gift.entryId(), ListingVisibility.HIDDEN);
        assertThat(detail("HELP-904").at("/counts/donors").asInt()).isZero();

        assertThatThrownBy(() -> ledger.restrictListing(gift.entryId(), ListingVisibility.NAMED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWriteThatWouldPassThePlatformBoundIsRefused() {
        jdbc.sql("update ledger_platform_total set gross_cents = 1000000000000000 - 100").update();
        long before = jdbc.sql("select count(*) from ledger_entry").query(Long.class).single();

        assertThatThrownBy(() -> ledger.recordDonation(caseId("HELP-904"), 200, WHEN, DonorListing.anonymous()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("platform_limit_reached");
                });
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isEqualTo(before);

        // Up to the bound is allowed.
        assertThat(ledger.recordDonation(caseId("HELP-904"), 100, WHEN, DonorListing.anonymous()).entryId()).isPositive();
    }
}
