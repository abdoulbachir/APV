package org.apvgakasinay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.PostgresIntegration;
import org.apvgakasinay.support.Repo;
import org.apvgakasinay.support.TestMedia;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Acceptance over the full shared dataset, contract/fixtures/dataset.json: 200 current cases,
 * two completed, two drafts, the general fund and 292 ledger entries, imported through the
 * same generic importer into real PostgreSQL.
 *
 * Expected values come from contract/fixtures/golden/acceptance.json and from the review
 * assignment. The application reads neither; it computes everything from the ledger rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullDatasetIT extends PostgresIntegration {

    private static final Pattern PICTURE = Pattern.compile("/media/public/[A-Za-z0-9_-]{22,64}/[a-z0-9-]+\\.webp");

    private final Map<String, Object> dataset = Json.readMap(Repo.contract("fixtures/dataset.json"));
    private final JsonNode golden = Json.tree(Json.readMap(Repo.contract("fixtures/golden/acceptance.json")));
    private final List<String> publicCodes = new ArrayList<>();
    private final List<String> draftCodes = new ArrayList<>();
    private final Set<String> privateMarkers = new LinkedHashSet<>();

    /** Loaded once: these tests only read. */
    @BeforeAll
    void load() {
        importer.replaceAllData(Json.copy(dataset));
        for (Map<String, Object> c : cases()) {
            boolean draft = "draft".equals(c.get("status"));
            (draft ? draftCodes : publicCodes).add((String) c.get("code"));
            privateMarkers.addAll(strings(c.get("private")));
            if (draft) {
                privateMarkers.add((String) c.get("publicName"));
                privateMarkers.addAll(strings(c.get("title")));
                privateMarkers.addAll(strings(c.get("story")));
            }
        }
        for (Map<String, Object> entry : list(dataset.get("ledger"))) {
            Map<String, Object> donor = map(entry.get("donor"));
            if (donor != null && !"named".equals(donor.get("visibility")) && donor.get("name") != null) {
                privateMarkers.add((String) donor.get("name"));
            }
        }
        privateMarkers.remove("");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Object mapOfStrings) {
        List<String> values = new ArrayList<>();
        map(mapOfStrings).values().forEach(v -> values.add((String) v));
        return values;
    }

    private List<Map<String, Object>> cases() {
        return list(dataset.get("cases"));
    }

    private JsonNode detail(String code) {
        return get("/api/cases/" + code).ok().json();
    }

    private void noPrivateValue(String where, String body) {
        for (String marker : privateMarkers) {
            assertThat(body).as(where).doesNotContain(marker);
        }
    }

    @Test
    void theDatasetIsTheOneDescribed() {
        assertThat(publicCodes).hasSize(203);
        assertThat(draftCodes).containsExactlyInAnyOrder("HELP-902", "HELP-906");
        assertThat(privateMarkers).hasSizeGreaterThan(400);
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isEqualTo(292);
        assertThat(jdbc.sql("select count(*) from category").query(Long.class).single()).isEqualTo(9);
        assertThat(get("/api/stats").ok().json().get("asOf").asText()).isEqualTo(dataset.get("clock"));
    }

    @Test
    void directoryPaging() {
        JsonNode first = valid("CasePage", get("/api/cases")).json();
        assertThat(first.get("totalItems").asInt()).isEqualTo(200);
        assertThat(first.get("totalPages").asInt()).isEqualTo(17);
        assertThat(first.get("pageSize").asInt()).isEqualTo(12);
        assertThat(first.get("items")).hasSize(12);
        assertThat(valid("CasePage", get("/api/cases", Map.of("page", "17"))).json().get("items")).hasSize(8);
        JsonNode beyond = valid("CasePage", get("/api/cases", Map.of("page", "18"))).json();
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("totalItems").asInt()).isEqualTo(200);
        assertThat(beyond.get("totalPages").asInt()).isEqualTo(17);

        JsonNode completed = valid("CasePage", get("/api/cases", Map.of("status", "completed"))).json();
        assertThat(completed.get("totalItems").asInt()).isEqualTo(2);
        assertThat(completed.get("totalPages").asInt()).isEqualTo(1);
        assertThat(get("/api/cases", Map.of("status", "completed")).codes()).containsExactlyInAnyOrder("HELP-903", "HELP-905");

        JsonNode largest = valid("CasePage", get("/api/cases", Map.of("pageSize", "48", "page", "5"))).json();
        assertThat(largest.get("totalPages").asInt()).isEqualTo(5);
        assertThat(largest.get("items")).hasSize(8);
    }

    @Test
    void goldenDirectoryExpectations() {
        for (JsonNode expectation : golden.get("directory")) {
            Map<String, String> query = new LinkedHashMap<>();
            expectation.get("query").fields().forEachRemaining(field -> query.put(field.getKey(), field.getValue().asText()));
            for (String language : List.of("en", "fr")) {
                Response response = valid("CasePage", get("/api/cases", query, language));
                JsonNode body = response.json();
                String where = query + " " + language;
                if (expectation.has("codes")) {
                    List<String> codes = new ArrayList<>();
                    expectation.get("codes").forEach(code -> codes.add(code.asText()));
                    assertThat(response.codes()).as(where).isEqualTo(codes);
                }
                if (expectation.has("itemCount")) {
                    assertThat(body.get("items")).as(where).hasSize(expectation.get("itemCount").asInt());
                }
                if (expectation.has("totalItems")) {
                    assertThat(body.get("totalItems").asInt()).as(where).isEqualTo(expectation.get("totalItems").asInt());
                }
                if (expectation.has("totalPages")) {
                    assertThat(body.get("totalPages").asInt()).as(where).isEqualTo(expectation.get("totalPages").asInt());
                }
            }
        }
    }

    /** Reads all 17 pages of one sort and returns the 200 cases in order. */
    private List<JsonNode> everyPage(String sort, String language) {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= 17; page++) {
            JsonNode body = valid("CasePage", get("/api/cases", Map.of("sort", sort, "page", String.valueOf(page)), language)).json();
            noPrivateValue("directory " + sort + " page " + page, body.toString());
            body.get("items").forEach(all::add);
        }
        assertThat(all).as(sort).hasSize(200);
        Set<String> codes = new HashSet<>();
        all.forEach(c -> codes.add(c.get("code").asText()));
        assertThat(codes).as(sort + ": every case exactly once across the pages").hasSize(200).doesNotContain("FUND").doesNotContainAnyElementsOf(draftCodes);
        return all;
    }

    private static final Comparator<JsonNode> TIE_BREAK = Comparator
            .comparing((JsonNode c) -> c.get("createdAt").asText(), Comparator.reverseOrder())
            .thenComparing(c -> c.get("code").asText());

    /** max(received, 0) / goal of a against b, by cross-multiplication. Both have a goal. */
    private static int compareRatio(JsonNode a, JsonNode b) {
        BigInteger left = BigInteger.valueOf(Math.max(a.get("received").asLong(), 0)).multiply(BigInteger.valueOf(b.get("goal").asLong()));
        BigInteger right = BigInteger.valueOf(Math.max(b.get("received").asLong(), 0)).multiply(BigInteger.valueOf(a.get("goal").asLong()));
        return left.compareTo(right);
    }

    private static <T> Comparator<T> nullsLast(java.util.function.Predicate<T> isNull, Comparator<T> whenBothPresent) {
        return (a, b) -> isNull.test(a) ? (isNull.test(b) ? 0 : 1) : isNull.test(b) ? -1 : whenBothPresent.compare(a, b);
    }

    /** The contract's ordering rules, written again here independently of the SQL. */
    private static Comparator<JsonNode> ruleFor(String sort) {
        Comparator<JsonNode> primary = switch (sort) {
            case "newest" -> (a, b) -> 0;
            case "deadline" -> nullsLast(c -> c.get("deadlineEndsAt").isNull(), Comparator.comparing(c -> c.get("deadlineEndsAt").asText()));
            case "featured" -> nullsLast(c -> c.get("featuredRank").isNull(), Comparator.comparingInt(c -> c.get("featuredRank").asInt()));
            case "least" -> nullsLast(c -> c.get("goal").isNull(), FullDatasetIT::compareRatio);
            case "closest" -> (a, b) -> {
                int groupA = a.get("goal").isNull() ? 2 : a.get("received").asLong() < a.get("goal").asLong() ? 0 : 1;
                int groupB = b.get("goal").isNull() ? 2 : b.get("received").asLong() < b.get("goal").asLong() ? 0 : 1;
                if (groupA != groupB) {
                    return groupA - groupB;
                }
                return groupA == 0 ? compareRatio(b, a) : groupA == 1 ? compareRatio(a, b) : 0;
            };
            default -> throw new IllegalArgumentException(sort);
        };
        return primary.thenComparing(TIE_BREAK);
    }

    @Test
    void allFiveSortsOrderAll200CasesByTheContractRules() {
        for (String sort : List.of("newest", "deadline", "closest", "least", "featured")) {
            List<JsonNode> served = everyPage(sort, "en");
            List<JsonNode> expected = new ArrayList<>(served);
            expected.sort(ruleFor(sort));
            assertThat(served.stream().map(c -> c.get("code").asText()).toList())
                    .as(sort)
                    .isEqualTo(expected.stream().map(c -> c.get("code").asText()).toList());
        }
        // The same order in French: sorting never depends on the language.
        assertThat(everyPage("closest", "fr").stream().map(c -> c.get("code").asText()).toList())
                .isEqualTo(everyPage("closest", "en").stream().map(c -> c.get("code").asText()).toList());
    }

    @Test
    void featuredCasesComeFirstInRankOrder() {
        assertThat(get("/api/cases", Map.of("sort", "featured", "pageSize", "3")).ok().codes()).containsExactly("HELP-014", "HELP-101", "HELP-103");
        JsonNode fourth = get("/api/cases", Map.of("sort", "featured", "pageSize", "4")).ok().json().at("/items/3");
        assertThat(fourth.get("featuredRank").isNull()).isTrue();
    }

    @Test
    void categoriesAreDataIncludingInactiveOnes() {
        JsonNode site = valid("Site", get("/api/site")).json();
        List<String> ids = new ArrayList<>();
        site.get("categories").forEach(category -> ids.add(category.get("id").asText() + (category.get("active").asBoolean() ? "" : " (inactive)")));
        assertThat(ids).containsExactly("education", "medical", "housing", "family", "emergency", "other", "food", "transport (inactive)");
        assertThat(get("/api/cases", Map.of("category", "food")).ok().codes()).containsExactly("HELP-102");
        assertThat(get("/api/cases", Map.of("category", "transport", "status", "completed")).ok().codes()).containsExactly("HELP-903");
        assertThat(get("/api/cases", Map.of("category", "transport")).ok().codes()).isEmpty();
        // "retired" exists only on drafts: it is a known identifier, lists nothing, and is not advertised.
        assertThat(get("/api/cases", Map.of("category", "retired")).ok().json().get("totalItems").asInt()).isZero();
        assertFieldError(get("/api/cases", Map.of("category", "sports")), "category", "invalid_value");
        int total = 0;
        for (String category : List.of("education", "medical", "housing", "family", "emergency", "other", "food")) {
            total += get("/api/cases", Map.of("category", category)).ok().json().get("totalItems").asInt();
        }
        assertThat(total).isEqualTo(200);
    }

    @Test
    void searchIgnoresAccentsAndCaseAndEnforcesItsLimits() {
        for (String query : List.of("  ÉLODIE  ", "elodie", "Élodie", "élodie", "ELODIE (SAMPLE)")) {
            assertThat(get("/api/cases", Map.of("q", query)).ok().codes()).as(query).containsExactly("HELP-106", "HELP-107");
        }
        assertThat(get("/api/cases", Map.of("q", "help-014")).ok().codes()).containsExactly("HELP-014");
        assertThat(get("/api/cases", Map.of("q", "incendie"), "en").ok().codes()).contains("HELP-014");
        assertThat(get("/api/cases", Map.of("q", "BROUILLON")).ok().json().get("totalItems").asInt()).isZero();
        assertThat(get("/api/cases", Map.of("q", "DRAFT-NAME")).ok().json().get("totalItems").asInt()).isZero();
        assertThat(get("/api/cases", Map.of("q", "x".repeat(80))).status()).isEqualTo(200);
        assertThat(get("/api/cases", Map.of("q", "🌍".repeat(80))).status()).isEqualTo(200);
        assertFieldError(get("/api/cases", Map.of("q", "x".repeat(81))), "q", "too_long");
        assertFieldError(get("/api/cases", Map.of("q", "🌍".repeat(81))), "q", "too_long");
        assertFieldError(get("/api/cases", Map.of("q", " ".repeat(201))), "q", "too_long");
    }

    @Test
    void bothLanguagesAndPerFieldFallback() {
        JsonNode french = valid("CaseDetail", get("/api/cases/HELP-014", Map.of(), "fr")).json();
        assertThat(french.at("/case/title").asText()).isEqualTo("Un nouveau départ après l’incendie");
        assertThat(french.at("/case/titleLanguage").asText()).isEqualTo("fr");
        assertThat(french.at("/case/storyLanguage").asText()).isEqualTo("fr");
        assertThat(french.at("/case/publicName").asText()).isEqualTo("Mariam D.");
        assertThat(french.at("/updates/0/textLanguage").asText()).isEqualTo("fr");
        JsonNode english = valid("CaseDetail", get("/api/cases/HELP-014", Map.of(), "en")).json();
        assertThat(english.at("/case/title").asText()).isNotEqualTo(french.at("/case/title").asText());
        assertThat(english.at("/updates/0/textLanguage").asText()).isEqualTo("en");
        assertThat(english.at("/ledger/0/label").asText()).isNotEqualTo(french.at("/ledger/0/label").asText());

        // HELP-101: an update and a gallery description written in English only.
        JsonNode fallback = valid("CaseDetail", get("/api/cases/HELP-101", Map.of(), "fr")).json();
        assertThat(fallback.at("/case/titleLanguage").asText()).isEqualTo("fr");
        assertThat(fallback.at("/updates/0/textLanguage").asText()).isEqualTo("en");
        assertThat(fallback.at("/photos/0/altLanguage").asText()).isEqualTo("en");
        assertThat(fallback.at("/case/photoAltLanguage").asText()).isEqualTo("fr");

        // Every one of the 200 directory entries is in the requested language.
        for (JsonNode c : everyPage("newest", "fr")) {
            assertThat(c.get("titleLanguage").asText()).isEqualTo("fr");
            assertThat(c.get("storyLanguage").asText()).isEqualTo("fr");
        }
    }

    @Test
    void theFinancialValuesNamedInTheReview() {
        assertMoney("HELP-014", 567500, 320000, 247500);
        assertMoney("FUND", 164000, 0, 164000);
        assertMoney("HELP-101", 38500, 30000, 8500);
        assertMoney("HELP-903", 24000, 25000, -1000);
        assertThat(detail("HELP-903").at("/financials/hasShortfall").asBoolean()).isTrue();

        JsonNode donors = valid("PublicDonorPage", get("/api/cases/HELP-101/donors")).json().get("items");
        JsonNode d3 = null;
        for (JsonNode donor : donors) {
            if (donor.get("id").asText().equals("D3")) {
                d3 = donor;
            }
        }
        assertThat(d3).isNotNull();
        assertThat(d3.get("amount").asLong()).isEqualTo(3500);
        assertThat(d3.get("name").isNull()).isTrue();
        assertThat(d3.get("avatarUrl").isNull()).isTrue();

        for (String missing : List.of("HELP-902", "HELP-906")) {
            for (String suffix : List.of("", "/updates", "/donors", "/ledger")) {
                error(get("/api/cases/" + missing + suffix), 404, "case_not_found");
            }
        }
    }

    @Test
    void goldenCaseExpectations() {
        golden.get("cases").fields().forEachRemaining(entry -> {
            JsonNode expected = entry.getValue();
            assertMoney(entry.getKey(), expected.get("received").asLong(), expected.get("delivered").asLong(), expected.get("available").asLong());
        });
        golden.get("missing").forEach(code -> error(get("/api/cases/" + code.asText()), 404, "case_not_found"));
    }

    private void assertMoney(String code, long allocated, long delivered, long available) {
        JsonNode detail = valid("CaseDetail", get("/api/cases/" + code)).json();
        assertThat(detail.at("/case/received").asLong()).as(code + " allocated").isEqualTo(allocated);
        assertThat(detail.at("/financials/allocated").asLong()).as(code + " allocated").isEqualTo(allocated);
        assertThat(detail.at("/case/delivered").asLong()).as(code + " delivered").isEqualTo(delivered);
        assertThat(detail.at("/financials/available").asLong()).as(code + " available").isEqualTo(available);
    }

    @Test
    void correctionsOnACompletedCase() {
        // HELP-905: a fully refunded gift, a gift entry reversed, a disbursement partly reversed.
        JsonNode detail = valid("CaseDetail", get("/api/cases/HELP-905")).json();
        assertThat(detail.at("/case/status").asText()).isEqualTo("closed");
        assertThat(detail.at("/financials/externalDonations").asLong()).isEqualTo(30000);
        assertThat(detail.at("/case/delivered").asLong()).isEqualTo(30000);
        assertThat(detail.at("/counts/ledger").asInt()).isEqualTo(8);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        detail.get("ledger").forEach(entry -> byId.put(entry.get("id").asText(), entry));
        assertThat(byId.get("H01").get("netAmount").asLong()).isZero();
        assertThat(byId.get("H01").get("correctedBy").toString()).isEqualTo("[\"H02\"]");
        assertThat(byId.get("H03").get("netAmount").asLong()).isZero();
        assertThat(byId.get("H06").get("netAmount").asLong()).isEqualTo(25000);
        // Gifts whose net amount is zero are not listed as donors.
        assertThat(detail.get("donors").toString()).doesNotContain("\"amount\":0");
        assertThat(detail.at("/counts/donors").asInt()).isLessThanOrEqualTo(1);
    }

    /**
     * Every public case: all four endpoints valid in both languages, nothing private, the
     * ledger adds up to the totals, and the cases together add up to the platform.
     */
    @Test
    void everyCaseReconcilesAndThePlatformIsTheSumOfItsCases() {
        long received = 0;
        long delivered = 0;
        long transfersIn = 0;
        long transfersOut = 0;
        long casesHelped = 0;
        long ledgerEntries = 0;
        int responses = 0;
        for (String code : publicCodes) {
            JsonNode detail = null;
            for (String language : List.of("en", "fr")) {
                Response response = valid("CaseDetail", get("/api/cases/" + code, Map.of(), language));
                noPrivateValue(code + " detail", response.body());
                noPrivateValue(code + " updates", valid("CaseUpdatePage", get("/api/cases/" + code + "/updates", Map.of(), language)).body());
                noPrivateValue(code + " ledger", valid("LedgerEntryPage", get("/api/cases/" + code + "/ledger", Map.of("pageSize", "48"), language)).body());
                detail = response.json();
                responses += 3;
            }
            Response donors = valid("PublicDonorPage", get("/api/cases/" + code + "/donors", Map.of("pageSize", "48")));
            noPrivateValue(code + " donors", donors.body());
            responses += 1;

            JsonNode f = detail.get("financials");
            long allocated = 0;
            long handedOver = 0;
            JsonNode ledger = get("/api/cases/" + code + "/ledger", Map.of("pageSize", "48")).ok().json();
            assertThat(ledger.get("totalItems").asInt()).as(code).isEqualTo(detail.at("/counts/ledger").asInt()).isLessThanOrEqualTo(48);
            for (JsonNode entry : ledger.get("items")) {
                allocated += entry.get("allocatedDelta").asLong();
                handedOver += entry.get("deliveredDelta").asLong();
                assertThat(entry.get("correctedAmount").asLong()).as(code).isLessThanOrEqualTo(entry.get("amount").asLong());
                assertThat(entry.get("netAmount").asLong()).as(code).isEqualTo(entry.get("amount").asLong() - entry.get("correctedAmount").asLong());
            }
            ledgerEntries += ledger.get("totalItems").asLong();
            assertThat(allocated).as(code + " ledger adds up to received").isEqualTo(detail.at("/case/received").asLong());
            assertThat(handedOver).as(code + " ledger adds up to delivered").isEqualTo(detail.at("/case/delivered").asLong());
            assertThat(f.get("allocated").asLong()).as(code)
                    .isEqualTo(f.get("externalDonations").asLong() + f.get("transfersIn").asLong() - f.get("transfersOut").asLong());
            assertThat(f.get("available").asLong()).as(code).isEqualTo(f.get("allocated").asLong() - f.get("delivered").asLong());
            assertThat(f.get("hasShortfall").asBoolean()).as(code).isEqualTo(f.get("available").asLong() < 0);

            long listed = 0;
            for (JsonNode donor : donors.json().get("items")) {
                listed += donor.get("amount").asLong();
            }
            assertThat(listed).as(code + " listed donors never exceed external donations").isLessThanOrEqualTo(f.get("externalDonations").asLong());

            received += detail.at("/case/received").asLong();
            delivered += detail.at("/case/delivered").asLong();
            transfersIn += f.get("transfersIn").asLong();
            transfersOut += f.get("transfersOut").asLong();
            if (detail.at("/case/kind").asText().equals("case") && detail.at("/case/delivered").asLong() > 0) {
                casesHelped++;
            }
        }
        JsonNode stats = valid("Stats", get("/api/stats")).json();
        assertThat(received).isEqualTo(stats.get("received").asLong());
        assertThat(delivered).isEqualTo(stats.get("delivered").asLong());
        assertThat(stats.get("available").asLong()).isEqualTo(received - delivered);
        assertThat(casesHelped).isEqualTo(stats.get("peopleHelped").asLong());
        assertThat(transfersIn).isEqualTo(transfersOut).isEqualTo(120000);
        assertThat(ledgerEntries).isEqualTo(292);
        assertThat(responses).isEqualTo(203 * 7);
        // The general fund holds money and allocates it; it never delivers.
        assertThat(detail("FUND").at("/case/delivered").asLong()).isZero();
    }

    @Test
    void donorPrivacyAcrossTheDataset() {
        int named = 0;
        int anonymous = 0;
        int withPicture = 0;
        for (String code : publicCodes) {
            for (JsonNode donor : get("/api/cases/" + code + "/donors", Map.of("pageSize", "48")).ok().json().get("items")) {
                if (donor.get("name").isNull()) {
                    anonymous++;
                    assertThat(donor.get("avatarUrl").isNull()).as(code + " anonymous donors never show a picture").isTrue();
                } else {
                    named++;
                }
                if (!donor.get("avatarUrl").isNull()) {
                    withPicture++;
                }
            }
        }
        // 210 gifts: 3 hidden are never listed, and two were corrected down to zero.
        assertThat(named + anonymous).isLessThan(210 - 3 + 1);
        assertThat(named).isLessThanOrEqualTo(6);
        assertThat(anonymous).isGreaterThan(190);
        // Exactly one donor is named and agreed to show a picture.
        assertThat(withPicture).isEqualTo(1);
    }

    @Test
    void deadlinesDaylightSavingAndAcceptingGifts() {
        JsonNode winter = detail("HELP-101").get("case");
        assertThat(winter.get("deadline").asText()).isEqualTo("2027-01-18");
        assertThat(winter.get("deadlineTimezone").asText()).isEqualTo("America/Chicago");
        assertThat(winter.get("deadlineEndsAt").asText()).isEqualTo("2027-01-19T06:00:00Z");
        // The day clocks move forward in Chicago: the cutoff is an hour earlier in UTC.
        assertThat(detail("HELP-102").at("/case/deadlineEndsAt").asText()).isEqualTo("2027-03-15T05:00:00Z");
        // Still in summer time when it ended.
        JsonNode past = detail("HELP-903").get("case");
        assertThat(past.get("deadlineEndsAt").asText()).isEqualTo("2026-11-01T05:00:00Z");
        assertThat(past.get("acceptingDonations").asBoolean()).isFalse();
        // A funded case keeps accepting gifts until paused or past its deadline.
        JsonNode funded = detail("HELP-103").get("case");
        assertThat(funded.get("status").asText()).isEqualTo("funded");
        assertThat(funded.get("received").asLong()).isGreaterThanOrEqualTo(funded.get("goal").asLong());
        assertThat(funded.get("acceptingDonations").asBoolean()).isTrue();
        assertThat(detail("HELP-905").at("/case/acceptingDonations").asBoolean()).isFalse();
        assertThat(detail("FUND").at("/case/acceptingDonations").asBoolean()).isTrue();
        // Every deadline in the directory has its zone and a cutoff after the date itself.
        for (JsonNode c : everyPage("deadline", "en")) {
            boolean hasDeadline = !c.get("deadline").isNull();
            assertThat(c.get("deadlineTimezone").isNull()).isEqualTo(!hasDeadline);
            assertThat(c.get("deadlineEndsAt").isNull()).isEqualTo(!hasDeadline);
            if (hasDeadline) {
                assertThat(c.get("deadlineEndsAt").asText()).isGreaterThan(c.get("deadline").asText());
            }
        }
    }

    @Test
    void historyPreviewsAndTheirPages() {
        JsonNode detail = valid("CaseDetail", get("/api/cases/HELP-101")).json();
        assertThat(detail.at("/counts/ledger").asInt()).isEqualTo(8);
        List<String> preview = new ArrayList<>();
        detail.get("ledger").forEach(entry -> preview.add(entry.get("id").asText()));
        assertThat(preview).containsExactly("L11", "L09", "L08", "L07", "L06", "L04", "L03", "L02");

        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            Response response = valid("LedgerEntryPage", get("/api/cases/HELP-101/ledger", Map.of("pageSize", "3", "page", String.valueOf(page))));
            assertThat(response.json().get("totalItems").asInt()).isEqualTo(8);
            assertThat(response.json().get("totalPages").asInt()).isEqualTo(3);
            paged.addAll(response.ids());
        }
        assertThat(paged).isEqualTo(preview);
        assertThat(get("/api/cases/HELP-101/ledger", Map.of("pageSize", "3", "page", "4")).ok().json().get("items")).isEmpty();

        assertThat(detail.at("/counts/donors").asInt()).isEqualTo(2);
        assertThat(valid("PublicDonorPage", get("/api/cases/HELP-101/donors", Map.of("pageSize", "1", "page", "2"))).ids()).containsExactly("D2");
        assertThat(detail.at("/counts/updates").asInt()).isEqualTo(1);
        assertThat(valid("CaseUpdatePage", get("/api/cases/HELP-014/updates")).ids()).containsExactly("U014");
        // The fund is read by its code, with its own history.
        JsonNode fund = valid("CaseDetail", get("/api/cases/fund")).json();
        assertThat(fund.at("/counts/ledger").asInt()).isEqualTo(5);
        assertThat(fund.at("/financials/transfersOut").asLong()).isEqualTo(120000);
    }

    /** Every picture address in every public response, with the file it must return. */
    private Set<String> everyPictureAddress() {
        Set<String> addresses = new LinkedHashSet<>();
        List<String> bodies = new ArrayList<>();
        for (int page = 1; page <= 17; page++) {
            bodies.add(get("/api/cases", Map.of("page", String.valueOf(page))).ok().body());
        }
        bodies.add(get("/api/cases", Map.of("status", "completed")).ok().body());
        for (String code : publicCodes) {
            bodies.add(get("/api/cases/" + code).ok().body());
            bodies.add(get("/api/cases/" + code + "/donors", Map.of("pageSize", "48")).ok().body());
            bodies.add(get("/api/cases/" + code + "/updates", Map.of("pageSize", "48")).ok().body());
        }
        for (String body : bodies) {
            Matcher matcher = PICTURE.matcher(body);
            while (matcher.find()) {
                addresses.add(matcher.group());
            }
            // Nothing that looks like a picture address may be anything else.
            assertThat(body).doesNotContain("http://").doesNotContain("https://").doesNotContain("images/");
        }
        return addresses;
    }

    @Test
    void everyPublicPictureIsServedWithItsContentAndCacheHeaders() throws IOException {
        Set<String> addresses = everyPictureAddress();
        // Two pictures (hope-window and new-home), each at display and thumbnail size.
        assertThat(addresses).hasSize(4);
        assertThat(addresses.stream().filter(a -> a.endsWith("/display.webp"))).hasSize(2);
        assertThat(addresses.stream().filter(a -> a.endsWith("/thumb.webp"))).hasSize(2);
        Set<Integer> sizes = new HashSet<>();
        for (String address : addresses) {
            Response response = get(address);
            assertThat(response.status()).as(address).isEqualTo(200);
            assertThat(response.raw().getContentType()).as(address).isEqualTo("image/webp");
            assertThat(response.raw().getHeader("Cache-Control")).as(address).contains("max-age=300").contains("public");
            byte[] served = response.raw().getContentAsByteArray();
            String token = address.split("/")[3];
            String storageKey = jdbc.sql("select storage_key from media_asset where token = :token").param("token", token).query(String.class).single();
            assertThat(served).as(address).isEqualTo(Files.readAllBytes(Repo.contract("fixtures").resolve(storageKey)));
            // RIFF....WEBP
            assertThat(new String(served, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(new String(served, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WEBP");
            sizes.add(served.length);
        }
        assertThat(sizes).containsExactlyInAnyOrder(77636, 108462);
    }

    @Test
    void unpublishedPicturesAndEscapesAreNeverServed() {
        List<String> unpublished = jdbc.sql("select token from media_asset where not published").query(String.class).list();
        assertThat(unpublished).isNotEmpty();
        // Point them at a file that exists: only the publication flag keeps them private.
        jdbc.sql("update media_asset set storage_key = 'images/new-home.webp' where not published").update();
        for (String token : unpublished) {
            assertThat(get("/media/public/" + token + "/display.webp").status()).as(token).isEqualTo(404);
            assertThat(get("/media/public/" + token + "/thumb.webp").status()).as(token).isEqualTo(404);
        }
        Set<String> served = everyPictureAddress();
        for (String token : unpublished) {
            assertThat(served).noneMatch(address -> address.contains(token));
        }

        // A published row whose file is a symbolic link pointing outside the picture folder.
        String published = jdbc.sql("select token from media_asset where published and storage_key = 'images/new-home.webp'").query(String.class).single();
        assertThat(get("/media/public/" + published + "/display.webp").status()).isEqualTo(200);
        assertThat(Files.isSymbolicLink(TestMedia.root().resolve(TestMedia.ESCAPING_LINK))).isTrue();
        jdbc.sql("update media_asset set storage_key = :key where token = :token").param("key", TestMedia.ESCAPING_LINK).param("token", published).update();
        try {
            Response escaped = get("/media/public/" + published + "/display.webp");
            assertThat(escaped.status()).isEqualTo(404);
            assertThat(escaped.body()).doesNotContain("outside the picture folder");
        } finally {
            jdbc.sql("update media_asset set storage_key = 'images/new-home.webp' where token = :token").param("token", published).update();
        }
        // The database refuses a key that climbs out of the folder in the first place.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("update media_asset set storage_key = '../secret.webp' where token = :token").param("token", published).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // A wrong format, a wrong token shape and a private route are simply not found.
        assertThat(get("/media/public/" + published + "/display.png").status()).isEqualTo(404);
        assertThat(get("/media/public/short/display.webp").status()).isEqualTo(404);
        assertThat(get("/media/private/" + published + "/display.webp").status()).isEqualTo(404);
        assertThat(get("/media/public/" + published + "/display.webp").status()).isEqualTo(200);
    }
}
