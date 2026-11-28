package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.Test;

/**
 * Scenarios 10.4, 10.6 and 10.7 of contract/tests/donation-acceptance.md, on a disposable
 * database loaded from a fixture. The same scenarios on a real database, with the fund the
 * migration inserts, are in ProductionProfileIT.
 */
class FundIT extends DonationIntegration {

    @Test
    void d10_4_aDisposableDatabaseHoldsTheFixturesFundAndTheLoaderNeverLeavesTwo() {
        assertThat(count("select count(*) from aid_case where kind = 'fund'")).isEqualTo(1);
        // The fixture's own fund, with the money the fixture gives it, not the empty one of the migration.
        assertThat(publicCase("FUND").at("/financials/available").asLong()).isEqualTo(40_000);
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
        assertThat(count("select count(*) from aid_case where kind = 'fund'")).isEqualTo(1);
        assertThat(count("select count(*) from aid_case where code = 'FUND'")).isEqualTo(1);
        // Loading a fixture replaces gifts with everything else; payment methods are records of the site and stay.
        zelle();
        report("FUND", 1000, currentDetails("zelle"));
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
        assertThat(count("select count(*) from donation")).isZero();
        assertThat(count("select count(*) from payment_method")).isEqualTo(1);
    }

    @Test
    void d10_6_d10_7_theFundReceivesGiftsLikeACaseAndNeverPaysABeneficiaryDirectly() {
        Reported gift = report("fund", 12_500, zelle());
        assertThat(gift.receipt().get("caseCode").asText()).isEqualTo("FUND");
        JsonNode before = publicCase("FUND");
        Staff owner = owner();
        confirm(owner, gift, "FUND", 12_500);

        JsonNode after = publicCase("FUND");
        assertThat(after.at("/financials/externalDonations").asLong()).isEqualTo(before.at("/financials/externalDonations").asLong() + 12_500);
        assertThat(after.at("/case/received").asLong()).isEqualTo(before.at("/case/received").asLong() + 12_500);
        assertThat(after.at("/ledger/0/kind").asText()).isEqualTo("donation");
        // No goal, so no amount makes it funded: its status never changes.
        assertThat(after.at("/case/status").asText()).isEqualTo("active");
        assertThat(count("select count(*) from audit_event where event_type like 'case.%'")).isZero();

        assertThatThrownBy(() -> jdbc.sql("""
                insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                values ('X1', (select id from aid_case where code = 'FUND'), 'disbursement', 100, date_trunc('second', now()))
                """).update()).hasMessageContaining("does not pay beneficiaries directly");
        assertThatThrownBy(() -> jdbc.sql("delete from aid_case where kind = 'fund'").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update aid_case set code = 'FUNDS' where kind = 'fund'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
