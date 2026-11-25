package org.apvgakasinay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.apvgakasinay.support.PostgresIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The financial rules the database enforces by itself, whatever the application does, and
 * what the public API shows afterwards. Starts from contract/fixtures/example.json.
 */
class LedgerRulesIT extends PostgresIntegration {

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void load() {
        loadExample();
    }

    private void insert(String id, String caseCode, String kind, long amount, String date, String corrects, String pairId) {
        jdbc.sql("""
                        insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at, corrects_id, pair_id)
                        values (:id, (select id from aid_case where code = :code), :kind, :amount, :date::timestamptz,
                                (select id from ledger_entry where public_id = :corrects::text), :pair)
                        """)
                .param("id", id).param("code", caseCode).param("kind", kind).param("amount", amount)
                .param("date", date).param("corrects", corrects).param("pair", pairId)
                .update();
    }

    private void assertRefused(String because, Runnable action) {
        long before = jdbc.sql("select count(*) from ledger_entry").query(Long.class).single();
        // A rule checked at commit surfaces as a transaction exception, so the whole cause chain is searched.
        assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class).hasStackTraceContaining(because);
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isEqualTo(before);
    }

    private JsonNode detail(String code) {
        return valid("CaseDetail", get("/api/cases/" + code)).json();
    }

    @Test
    void theLedgerIsAppendOnly() {
        assertRefused("append-only", () -> jdbc.sql("update ledger_entry set amount_cents = 1 where public_id = 'L02'").update());
        assertRefused("append-only", () -> jdbc.sql("delete from ledger_entry where public_id = 'L14'").update());
    }

    @Test
    void refundsCannotExceedTheDonation() {
        // L03 is 10000 with 6500 already refunded: 3500 is left.
        assertRefused("more than its amount", () -> insert("X1", "HELP-901", "refund", 3501, "2026-11-20T12:00:00Z", "L03", null));
        insert("X1", "HELP-901", "refund", 3500, "2026-11-20T12:00:00Z", "L03", null);
        assertRefused("more than its amount", () -> insert("X2", "HELP-901", "refund", 1, "2026-11-21T12:00:00Z", "L03", null));

        // Fully refunded: gone from the donor list, still in the ledger, totals reduced.
        JsonNode detail = detail("HELP-901");
        assertThat(detail.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(detail.get("donors").toString()).doesNotContain("D3");
        assertThat(detail.at("/counts/ledger").asInt()).isEqualTo(9);
        assertThat(detail.at("/financials/externalDonations").asLong()).isEqualTo(25000);
        JsonNode l03 = get("/api/cases/HELP-901/ledger", Map.of("pageSize", "48")).ok().json().get("items").get(7);
        assertThat(l03.get("id").asText()).isEqualTo("L03");
        assertThat(l03.get("correctedBy").toString()).isEqualTo("[\"L08\",\"L09\",\"X1\"]");
        assertThat(l03.get("correctedAmount").asLong()).isEqualTo(10000);
        assertThat(l03.get("netAmount").asLong()).isZero();
        assertThat(get("/api/stats").ok().json().get("received").asLong()).isEqualTo(102500 - 3500);
    }

    @Test
    void correctionsMustPointAtTheRightKindOfEntry() {
        assertRefused("can only correct a donation", () -> insert("X1", "HELP-901", "refund", 100, "2026-11-20T12:00:00Z", "L07", null));
        assertRefused("can only correct a donation", () -> insert("X1", "HELP-901", "refund", 100, "2026-11-20T12:00:00Z", "L08", null));
        assertRefused("can only correct a disbursement",
                () -> insert("X1", "HELP-901", "disbursement_reversal", 100, "2026-11-20T12:00:00Z", "L02", null));
        assertRefused("must name the entry it corrects", () -> insert("X1", "HELP-901", "refund", 100, "2026-11-20T12:00:00Z", null, null));
        assertRefused("cannot correct another entry", () -> insert("X1", "HELP-901", "donation", 100, "2026-11-20T12:00:00Z", "L02", null));
        assertRefused("on the case of the entry it corrects", () -> insert("X1", "HELP-903", "refund", 100, "2026-11-20T12:00:00Z", "L03", null));
        assertRefused("dated before", () -> insert("X1", "HELP-901", "refund", 100, "2026-11-01T12:00:00Z", "L03", null));
    }

    @Test
    void aDisbursementCanBeReversedInPart() {
        insert("X1", "HELP-901", "disbursement_reversal", 12000, "2026-11-20T12:00:00Z", "L07", null);
        JsonNode detail = detail("HELP-901");
        assertThat(detail.at("/case/delivered").asLong()).isEqualTo(18000);
        assertThat(detail.at("/financials/available").asLong()).isEqualTo(20500);
        assertThat(detail.at("/ledger/0/deliveredDelta").asLong()).isEqualTo(-12000);
        assertThat(get("/api/stats").ok().json().get("delivered").asLong()).isEqualTo(43000);
    }

    @Test
    void noMoneyMovesOnADraft() {
        assertRefused("draft case", () -> insert("X1", "HELP-902", "donation", 100, "2026-11-20T12:00:00Z", null, null));
    }

    @Test
    void aPublishedCaseNeverBecomesADraftAgain() {
        assertThatThrownBy(() -> jdbc.sql("update aid_case set status = 'draft' where code = 'HELP-904'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot return to draft");
        assertThatThrownBy(() -> jdbc.sql("update aid_case set title_fr = null where code = 'HELP-904'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from aid_case where code = 'HELP-901'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update category set id = 'renamed' where id = 'food'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never change");
    }

    @Test
    void amountsAreBounded() {
        assertRefused("ledger_entry", () -> insert("X1", "HELP-904", "donation", 10_000_000_001L, "2026-11-20T12:00:00Z", null, null));
        assertRefused("ledger_entry", () -> insert("X1", "HELP-904", "donation", 0, "2026-11-20T12:00:00Z", null, null));
        assertRefused("ledger_entry", () -> insert("X1", "HELP-904", "donation", 100, "2026-11-20T12:00:00.5Z", null, null));
        insert("X1", "HELP-904", "donation", 10_000_000_000L, "2026-11-20T12:00:00Z", null, null);
        assertThat(detail("HELP-904").at("/case/received").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    void discretionarySpendingCannotCreateAShortfall() {
        // HELP-901 holds 8500.
        assertRefused("would leave case HELP-901", () -> insert("X1", "HELP-901", "disbursement", 8501, "2026-11-20T12:00:00Z", null, null));
        insert("X1", "HELP-901", "disbursement", 8500, "2026-11-20T12:00:00Z", null, null);
        assertThat(detail("HELP-901").at("/financials/available").asLong()).isZero();
    }

    @Test
    void aRealRefundIsRecordedEvenWhenItCreatesAShortfall() {
        // HELP-903 already has a shortfall of 1000. A further refund is a fact and is recorded.
        insert("X1", "HELP-903", "refund", 4000, "2026-11-21T12:00:00Z", "L12", null);
        JsonNode detail = detail("HELP-903");
        assertThat(detail.at("/financials/available").asLong()).isEqualTo(-5000);
        assertThat(detail.at("/financials/hasShortfall").asBoolean()).isTrue();
        assertThat(detail.at("/case/received").asLong()).isEqualTo(20000);
        assertThat(detail.at("/case/delivered").asLong()).isEqualTo(25000);
        // While the shortfall lasts, nothing discretionary leaves the case.
        assertRefused("would leave case HELP-903", () -> insert("X2", "HELP-903", "disbursement", 1, "2026-11-22T12:00:00Z", null, null));
        // The platform still reconciles.
        JsonNode stats = get("/api/stats").ok().json();
        assertThat(stats.get("received").asLong()).isEqualTo(98500);
        assertThat(stats.get("available").asLong()).isEqualTo(43500);
    }

    @Test
    void aTransferNeedsBothSides() {
        assertRefused("transfer pair", () -> insert("X1", "FUND", "transfer_out", 1000, "2026-11-20T12:00:00Z", null, "T9"));
        assertRefused("pair_id belongs on transfer kinds", () -> insert("X1", "FUND", "transfer_out", 1000, "2026-11-20T12:00:00Z", null, null));
        assertRefused("pair_id belongs on transfer kinds", () -> insert("X1", "FUND", "donation", 1000, "2026-11-20T12:00:00Z", null, "T9"));
        TransactionTemplate together = new TransactionTemplate(transactions);
        assertRefused("transfer pair", () -> together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out", 1000, "2026-11-20T12:00:00Z", null, "T9");
            insert("X2", "HELP-904", "transfer_in", 999, "2026-11-20T12:00:00Z", null, "T9");
        }));
        assertRefused("transfer pair", () -> together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out", 1000, "2026-11-20T12:00:00Z", null, "T9");
            insert("X2", "FUND", "transfer_in", 1000, "2026-11-20T12:00:00Z", null, "T9");
        }));
        // The fund cannot give more than it holds (40000).
        assertRefused("would leave case FUND", () -> together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out", 40001, "2026-11-20T12:00:00Z", null, "T9");
            insert("X2", "HELP-904", "transfer_in", 40001, "2026-11-20T12:00:00Z", null, "T9");
        }));
    }

    @Test
    void aTransferMovesMoneyWithoutChangingPlatformTotals() {
        JsonNode before = get("/api/stats").ok().json();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out", 6000, "2026-11-20T12:00:00Z", null, "T9");
            insert("X2", "HELP-904", "transfer_in", 6000, "2026-11-20T12:00:00Z", null, "T9");
        });
        JsonNode after = get("/api/stats").ok().json();
        assertThat(after.get("received").asLong()).isEqualTo(before.get("received").asLong());
        assertThat(after.get("delivered").asLong()).isEqualTo(before.get("delivered").asLong());
        assertThat(detail("FUND").at("/financials/transfersOut").asLong()).isEqualTo(16000);
        assertThat(detail("FUND").at("/financials/available").asLong()).isEqualTo(34000);
        JsonNode receiver = detail("HELP-904");
        assertThat(receiver.at("/case/received").asLong()).isEqualTo(6000);
        assertThat(receiver.at("/financials/externalDonations").asLong()).isZero();
        assertThat(receiver.at("/financials/transfersIn").asLong()).isEqualTo(6000);
        assertThat(receiver.at("/ledger/0/counterpartCaseCode").asText()).isEqualTo("FUND");
        // A transfer in creates no donor listing.
        assertThat(receiver.at("/counts/donors").asInt()).isZero();
    }

    @Test
    void aTransferReversalMustUndoBothSidesOfOneTransfer() {
        TransactionTemplate together = new TransactionTemplate(transactions);
        // Only one side.
        assertRefused("transfer pair", () -> insert("X1", "FUND", "transfer_out_reversal", 1000, "2026-11-20T12:00:00Z", "L05", "T9"));
        // More than what is left of the transfer (15000 moved, 5000 already undone).
        assertRefused("more than its amount", () -> together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out_reversal", 10001, "2026-11-20T12:00:00Z", "L05", "T9");
            insert("X2", "HELP-901", "transfer_in_reversal", 10001, "2026-11-20T12:00:00Z", "L06", "T9");
        }));
        // HELP-901 holds 8500: taking 9000 back would leave it below zero.
        assertRefused("would leave case HELP-901", () -> together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out_reversal", 9000, "2026-11-20T12:00:00Z", "L05", "T9");
            insert("X2", "HELP-901", "transfer_in_reversal", 9000, "2026-11-20T12:00:00Z", "L06", "T9");
        }));
        together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out_reversal", 8000, "2026-11-20T12:00:00Z", "L05", "T9");
            insert("X2", "HELP-901", "transfer_in_reversal", 8000, "2026-11-20T12:00:00Z", "L06", "T9");
        });
        assertThat(detail("HELP-901").at("/financials/transfersIn").asLong()).isEqualTo(2000);
        assertThat(detail("FUND").at("/financials/transfersOut").asLong()).isEqualTo(2000);
        assertThat(get("/api/stats").ok().json().get("received").asLong()).isEqualTo(102500);
    }

    @Test
    void theGeneralFundNeverPaysABeneficiaryDirectly() {
        // The fund holds 40000, so this is not refused for lack of money.
        assertThat(detail("FUND").at("/financials/available").asLong()).isEqualTo(40000);
        assertRefused("does not pay beneficiaries directly", () -> insert("X1", "FUND", "disbursement", 100, "2026-11-20T12:00:00Z", null, null));
        assertThat(detail("FUND").at("/case/delivered").asLong()).isZero();
        // The approved route still works: transfer to a case, deliver from the case, and undo part of the transfer.
        TransactionTemplate together = new TransactionTemplate(transactions);
        together.executeWithoutResult(status -> {
            insert("X1", "FUND", "transfer_out", 5000, "2026-11-20T12:00:00Z", null, "T9");
            insert("X2", "HELP-904", "transfer_in", 5000, "2026-11-20T12:00:00Z", null, "T9");
        });
        insert("X3", "HELP-904", "disbursement", 3000, "2026-11-21T12:00:00Z", null, null);
        together.executeWithoutResult(status -> {
            insert("X4", "FUND", "transfer_out_reversal", 2000, "2026-11-22T12:00:00Z", "X1", "T10");
            insert("X5", "HELP-904", "transfer_in_reversal", 2000, "2026-11-22T12:00:00Z", "X2", "T10");
        });
        assertThat(detail("FUND").at("/financials/available").asLong()).isEqualTo(37000);
        assertThat(detail("FUND").at("/case/delivered").asLong()).isZero();
        assertThat(detail("HELP-904").at("/case/delivered").asLong()).isEqualTo(3000);
        assertThat(detail("HELP-904").at("/financials/available").asLong()).isZero();
        assertThat(get("/api/stats").ok().json().get("delivered").asLong()).isEqualTo(58000);
    }

    @Test
    void thePlatformTotalFollowsTheLedger() {
        assertThat(platformTotal()).isEqualTo(ledgerSum()).isEqualTo(new java.math.BigDecimal("212500"));
        insert("X1", "HELP-904", "donation", 1234, "2026-11-20T12:00:00Z", null, null);
        assertThat(platformTotal()).isEqualTo(ledgerSum());
        // A refused insert leaves the total untouched.
        assertRefused("would leave case", () -> insert("X2", "HELP-904", "disbursement", 999_999, "2026-11-21T12:00:00Z", null, null));
        assertThat(platformTotal()).isEqualTo(ledgerSum());
        // Nobody edits the total past the bound by hand either.
        assertThatThrownBy(() -> jdbc.sql("update ledger_platform_total set gross_cents = 1000000000000001").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ledger_platform_total_within_bound");
    }

    private java.math.BigDecimal platformTotal() {
        return jdbc.sql("select gross_cents from ledger_platform_total").query(java.math.BigDecimal.class).single();
    }

    private java.math.BigDecimal ledgerSum() {
        return jdbc.sql("select coalesce(sum(amount_cents), 0)::numeric(20, 0) from ledger_entry").query(java.math.BigDecimal.class).single();
    }

    /**
     * Two transactions insert on two different cases at the same time, with room for only one
     * of them under the platform bound. The second must wait for the first, then be refused.
     * (The total is moved next to the bound by hand; reaching it for real takes 100,000
     * maximal movements.)
     */
    @Test
    void thePlatformBoundHoldsWhenTwoCasesAreWrittenAtTheSameTime() throws Exception {
        jdbc.sql("update ledger_platform_total set gross_cents = 1000000000000000 - 1000").update();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            try (Statement statement = first.createStatement()) {
                statement.executeUpdate("""
                        insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                        values ('X1', (select id from aid_case where code = 'HELP-904'), 'donation', 600, '2026-11-20T12:00:00Z')
                        """);
            }
            java.util.concurrent.Future<Throwable> second = executor.submit(() -> {
                try {
                    insert("X2", "HELP-901", "donation", 600, "2026-11-20T12:00:00Z", null, null);
                    return null;
                } catch (Throwable refused) {
                    return refused;
                }
            });
            // While the first transaction is open, the second cannot finish either way.
            assertThatThrownBy(() -> second.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            first.commit();
            Throwable outcome = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome).as("the second insert must be refused").isNotNull();
            assertThat(outcome).hasStackTraceContaining("ledger_platform_total_within_bound");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from ledger_entry where public_id = 'X1'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from ledger_entry where public_id = 'X2'").query(Long.class).single()).isZero();
        assertThat(platformTotal()).isEqualTo(new java.math.BigDecimal("999999999999600"));
    }

    @Test
    void donorListingsBelongToDonations() {
        assertThatThrownBy(() -> jdbc.sql("""
                insert into donation_listing (ledger_entry_id, public_id, visibility)
                values ((select id from ledger_entry where public_id = 'L07'), 'DX', 'named')
                """).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("belongs to a donation");
    }

    /**
     * Opens one repeatable-read transaction, reads, lets another connection commit a new
     * donation, and reads again inside the same transaction: nothing may change. This is the
     * transaction every public read runs in.
     */
    @Test
    void everythingInOneReadComesFromOneSnapshot(@Autowired org.apvgakasinay.cases.CaseReadService cases,
                                                @Autowired org.apvgakasinay.site.SiteService site) {
        TransactionTemplate snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.executeWithoutResult(status -> {
            long receivedBefore = site.stats().received();
            var before = cases.detail("HELP-904", org.apvgakasinay.shared.Language.EN);

            try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
                statement.executeUpdate("""
                        insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                        values ('X1', (select id from aid_case where code = 'HELP-904'), 'donation', 7700, '2026-11-20T12:00:00Z')
                        """);
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }

            var after = cases.detail("HELP-904", org.apvgakasinay.shared.Language.EN);
            assertThat(after).isEqualTo(before);
            assertThat(after.caseView().received()).isZero();
            assertThat(after.counts().ledger()).isZero();
            assertThat(site.stats().received()).isEqualTo(receivedBefore);
        });
        // A new read sees the committed donation, consistently in every number.
        JsonNode fresh = detail("HELP-904");
        assertThat(fresh.at("/case/received").asLong()).isEqualTo(7700);
        assertThat(fresh.at("/financials/externalDonations").asLong()).isEqualTo(7700);
        assertThat(fresh.at("/counts/ledger").asInt()).isEqualTo(1);
        assertThat(fresh.get("ledger")).hasSize(1);
        assertThat(get("/api/stats").ok().json().get("received").asLong()).isEqualTo(110200);
    }
}
