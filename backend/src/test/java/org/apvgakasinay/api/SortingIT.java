package org.apvgakasinay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.support.PostgresIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic sorting, exact funding ratios and tie-breakers, on a dataset built for the
 * purpose. It goes through the same generic importer as any fixture file.
 */
class SortingIT extends PostgresIntegration {

    private final List<Map<String, Object>> cases = new ArrayList<>();
    private final List<Map<String, Object>> ledger = new ArrayList<>();

    @BeforeEach
    void load() {
        Map<String, Object> fixture = exampleFixture();
        addCase("FUND", "fund", "2026-01-01T00:00:00Z", null, null, null, null);
        // One third, written two ways: an exact tie, broken by the newer case first.
        addCase("AA-1", "case", "2026-01-01T12:00:00Z", 3L, "2027-02-01", "America/Chicago", null);
        addCase("AA-2", "case", "2026-01-02T12:00:00Z", 6L, "2027-02-01", "Pacific/Kiritimati", null);
        // Just under one third.
        addCase("AA-3", "case", "2026-01-03T12:00:00Z", 10_000_000_000L, null, null, 2);
        addCase("AA-4", "case", "2026-01-04T12:00:00Z", 100_000L, null, null, null);
        // Exactly at goal, and over it.
        addCase("AA-5", "case", "2026-01-05T12:00:00Z", 1_000L, "2026-12-01", "America/Chicago", null);
        addCase("AA-6", "case", "2026-01-06T12:00:00Z", 1_000L, null, null, null);
        // No goal.
        addCase("AA-7", "case", "2026-01-07T12:00:00Z", null, null, null, 1);
        // Same creation instant: the code decides, compared as text, so AA-10 comes before AA-9.
        addCase("AA-9", "case", "2026-01-08T12:00:00Z", null, null, null, null);
        addCase("AA-10", "case", "2026-01-08T12:00:00Z", null, null, null, null);
        // Two ratios that differ by 10^-20. Floating point cannot tell them apart;
        // 9999999999/10000000000 is the larger.
        addCase("AA-11", "case", "2026-01-09T12:00:00Z", 10_000_000_000L, null, null, null);
        addCase("AA-12", "case", "2026-01-10T12:00:00Z", 9_999_999_999L, null, null, null);

        give("AA-1", 1);
        give("AA-2", 2);
        give("AA-3", 3_333_333_333L);
        give("AA-5", 1_000);
        give("AA-6", 1_500);
        give("AA-7", 500);
        give("AA-11", 9_999_999_999L);
        give("AA-12", 9_999_999_998L);

        fixture.put("cases", cases);
        fixture.put("ledger", ledger);
        fixture.put("updates", List.of());
        importer.replaceAllData(fixture);
    }

    private void addCase(String code, String kind, String createdAt, Long goal, String deadline, String zone, Integer rank) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("kind", kind);
        c.put("status", "active");
        c.put("acceptsDonations", true);
        c.put("category", kind.equals("fund") ? null : "other");
        c.put("featuredRank", rank);
        c.put("publicName", kind.equals("fund") ? "" : "Sample " + code);
        c.put("title", Map.of("en", "Sample " + code, "fr", "Exemple " + code));
        c.put("story", Map.of("en", "Fictional.", "fr", "Fictif."));
        c.put("goal", goal);
        c.put("deadline", deadline);
        c.put("deadlineTimezone", zone);
        c.put("createdAt", createdAt);
        c.put("cover", null);
        c.put("photos", List.of());
        c.put("private", Map.of("realName", "", "contact", "", "notes", ""));
        cases.add(c);
    }

    private void give(String code, long amount) {
        Map<String, Object> donor = new LinkedHashMap<>();
        donor.put("listingId", "D-" + code);
        donor.put("visibility", "hidden");
        donor.put("name", null);
        donor.put("avatarConsent", false);
        donor.put("avatarFile", null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "L-" + code);
        entry.put("caseCode", code);
        entry.put("kind", "donation");
        entry.put("amount", amount);
        entry.put("date", "2026-02-01T12:00:00Z");
        entry.put("corrects", null);
        entry.put("pairId", null);
        entry.put("donor", donor);
        ledger.add(entry);
    }

    private List<String> sorted(String sort) {
        return valid("CasePage", get("/api/cases", Map.of("sort", sort, "pageSize", "48"))).codes();
    }

    @Test
    void newest() {
        assertThat(sorted("newest")).containsExactly(
                "AA-12", "AA-11", "AA-10", "AA-9", "AA-7", "AA-6", "AA-5", "AA-4", "AA-3", "AA-2", "AA-1");
    }

    @Test
    void closestToGoalUsesExactRatios() {
        assertThat(sorted("closest")).containsExactly(
                // Below goal, highest ratio first. AA-11 beats AA-12 by 10^-20.
                "AA-11", "AA-12", "AA-2", "AA-1", "AA-3", "AA-4",
                // At or above goal, lowest ratio first.
                "AA-5", "AA-6",
                // No goal, by the tie-breakers.
                "AA-10", "AA-9", "AA-7");
    }

    @Test
    void leastFunded() {
        assertThat(sorted("least")).containsExactly(
                "AA-4", "AA-3", "AA-2", "AA-1", "AA-12", "AA-11", "AA-5", "AA-6",
                "AA-10", "AA-9", "AA-7");
    }

    @Test
    void featuredFirstThenNewest() {
        assertThat(sorted("featured")).containsExactly(
                "AA-7", "AA-3", "AA-12", "AA-11", "AA-10", "AA-9", "AA-6", "AA-5", "AA-4", "AA-2", "AA-1");
    }

    @Test
    void deadlineOrdersByTheRealCutoffNotTheDateText() {
        // AA-2 and AA-1 share the date 2027-02-01, but AA-2's day ends 20 hours earlier in its zone.
        assertThat(sorted("deadline")).startsWith("AA-5", "AA-2", "AA-1").hasSize(11);
        assertThat(sorted("deadline").subList(3, 11)).containsExactly(
                "AA-12", "AA-11", "AA-10", "AA-9", "AA-7", "AA-6", "AA-4", "AA-3");
        JsonNode kiritimati = get("/api/cases/AA-2").ok().json().get("case");
        assertThat(kiritimati.get("deadlineEndsAt").asText()).isEqualTo("2027-02-01T10:00:00Z");
        assertThat(kiritimati.get("deadlineTimezone").asText()).isEqualTo("Pacific/Kiritimati");
        assertThat(get("/api/cases/AA-1").ok().json().at("/case/deadlineEndsAt").asText()).isEqualTo("2027-02-02T06:00:00Z");
    }

    @Test
    void pagesDoNotOverlapAndCoverEverything() {
        List<String> all = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode body = valid("CasePage", get("/api/cases", Map.of("sort", "closest", "pageSize", "5", "page", String.valueOf(page)))).json();
            assertThat(body.get("totalItems").asInt()).isEqualTo(11);
            assertThat(body.get("totalPages").asInt()).isEqualTo(3);
            body.get("items").forEach(item -> all.add(item.get("code").asText()));
        }
        assertThat(all).isEqualTo(sorted("closest"));
        assertThat(get("/api/cases", Map.of("pageSize", "5", "page", "4")).ok().json().get("items")).isEmpty();
    }

    @Test
    void largeAmountsStayExact() {
        JsonNode detail = valid("CaseDetail", get("/api/cases/AA-11")).json();
        assertThat(detail.at("/case/received").asLong()).isEqualTo(9_999_999_999L);
        assertThat(detail.at("/case/goal").asLong()).isEqualTo(10_000_000_000L);
        JsonNode stats = valid("Stats", get("/api/stats")).json();
        assertThat(stats.get("received").asLong()).isEqualTo(1 + 2 + 3_333_333_333L + 1_000 + 1_500 + 500 + 9_999_999_999L + 9_999_999_998L);
        // Hidden donors are counted and never listed.
        assertThat(detail.at("/counts/donors").asInt()).isZero();
    }
}
