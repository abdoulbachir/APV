package org.apvgakasinay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.PostgresIntegration;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The seven endpoints over contract/fixtures/example.json.
 *
 * Expected numbers come from contract/fixtures/example.expected.json. The application never
 * reads that file: it computes every total from the ledger rows the importer stored.
 */
class ExampleFixtureIT extends PostgresIntegration {

    private static final List<String> LANGUAGES = List.of("en", "fr");
    private static final List<String> SORTS = List.of("newest", "deadline", "closest", "least", "featured");

    private final JsonNode expected = Json.tree(Json.readMap(Repo.contract("fixtures/example.expected.json")));

    @BeforeEach
    void load() {
        loadExample();
    }

    /** Every public URL of the example, with the schema its response must match. */
    private Map<String, String> everyPublicResponse() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("GET /site", valid("Site", get("/api/site")).body());
        bodies.put("GET /stats", valid("Stats", get("/api/stats")).body());
        for (String language : LANGUAGES) {
            for (String status : List.of("active", "completed")) {
                for (String sort : SORTS) {
                    bodies.put("GET /cases " + status + " " + sort + " " + language,
                            valid("CasePage", get("/api/cases", Map.of("status", status, "sort", sort), language)).body());
                }
            }
            for (String code : List.of("FUND", "HELP-901", "HELP-903", "HELP-904")) {
                bodies.put("GET /cases/" + code + " " + language, valid("CaseDetail", get("/api/cases/" + code, Map.of(), language)).body());
                bodies.put("GET /cases/" + code + "/updates " + language,
                        valid("CaseUpdatePage", get("/api/cases/" + code + "/updates", Map.of(), language)).body());
                bodies.put("GET /cases/" + code + "/donors " + language,
                        valid("PublicDonorPage", get("/api/cases/" + code + "/donors", Map.of(), language)).body());
                bodies.put("GET /cases/" + code + "/ledger " + language,
                        valid("LedgerEntryPage", get("/api/cases/" + code + "/ledger", Map.of(), language)).body());
            }
        }
        return bodies;
    }

    @Test
    void everyResponseIsValidAgainstTheContract() {
        // The seven public read endpoints are unchanged since 0.2.0; 0.3.0 adds the identity
        // endpoints and 0.4.0 the donation lifecycle. Both are additive to the public reads.
        // 0.4.1 makes the private note of a reversal and of a refund required (owner decision of 2026-11-28).
        assertThat(contract.version()).isEqualTo("0.4.1");
        assertThat(everyPublicResponse()).hasSize(2 + 2 * (10 + 16));
    }

    @Test
    void noPrivateValueAppearsInAnyResponse() {
        List<String> markers = new ArrayList<>();
        expected.get("mustNeverAppearInAnyPublicResponse").forEach(marker -> markers.add(marker.asText()));
        assertThat(markers).hasSizeGreaterThan(10);
        everyPublicResponse().forEach((url, body) -> assertThat(body).as(url).doesNotContain(markers));
        // Error responses too.
        assertThat(get("/api/cases/HELP-902").body()).doesNotContain(markers);
    }

    @Test
    void theExamplesWrittenInTheContractAreWhatTheApplicationReturns() {
        Map<String, Response> actual = new LinkedHashMap<>();
        actual.put("getSite", get("/api/site"));
        actual.put("getStats", get("/api/stats"));
        actual.put("listCases", get("/api/cases", Map.of("pageSize", "1")));
        actual.put("getCase", get("/api/cases/HELP-901"));
        actual.put("listCaseUpdates", get("/api/cases/HELP-901/updates"));
        actual.put("listCaseDonors", get("/api/cases/HELP-901/donors"));
        actual.put("listCaseLedger", get("/api/cases/HELP-901/ledger", Map.of("pageSize", "2")));
        actual.forEach((operationId, response) ->
                assertThat(maskPictureTokens(Json.canonical(response.ok().json())))
                        .as(operationId)
                        .isEqualTo(maskPictureTokens(Json.canonical(contract.example(operationId)))));
    }

    /** Picture tokens are random on a real server; the contract's examples use made-up ones. */
    private static String maskPictureTokens(String json) {
        return json.replaceAll("/media/public/[A-Za-z0-9_-]+/", "/media/public/TOKEN/");
    }

    @Test
    void platformTotalsMatchTheExpectedValues() {
        JsonNode stats = get("/api/stats").ok().json();
        for (String field : List.of("received", "delivered", "available", "peopleHelped")) {
            assertThat(stats.get(field).asLong()).as(field).isEqualTo(expected.at("/stats/" + field).asLong());
        }
        assertThat(stats.get("asOf").asText()).isEqualTo("2026-11-24T12:00:00Z");
    }

    @Test
    void siteListsCategoriesAsData() {
        JsonNode site = get("/api/site").ok().json();
        assertThat(site.get("timezone").asText()).isEqualTo("America/Chicago");
        List<Map<String, Object>> listed = new ArrayList<>();
        site.get("categories").forEach(category -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", category.get("id").asText());
            entry.put("active", category.get("active").asBoolean());
            listed.add(entry);
            assertThat(category.at("/labels/en").asText()).isNotBlank();
            assertThat(category.at("/labels/fr").asText()).isNotBlank();
        });
        assertThat(Json.canonical(listed)).isEqualTo(Json.canonical(expected.at("/site/categories")));
        // The seventh category exists with no code change; the one used only by a draft is not listed.
        assertThat(site.get("categories").toString()).contains("\"food\"").contains("Alimentation").doesNotContain("retired");
    }

    @Test
    void everyCaseMatchesItsExpectedValues() {
        expected.get("cases").fields().forEachRemaining(entry -> {
            String code = entry.getKey();
            if (entry.getValue().isTextual()) {
                error(get("/api/cases/" + code), 404, "case_not_found");
                error(get("/api/cases/" + code + "/ledger"), 404, "case_not_found");
                error(get("/api/cases/" + code + "/donors"), 404, "case_not_found");
                error(get("/api/cases/" + code + "/updates"), 404, "case_not_found");
                return;
            }
            JsonNode detail = get("/api/cases/" + code).ok().json();
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("acceptingDonations", detail.at("/case/acceptingDonations"));
            actual.put("deadlineEndsAt", detail.at("/case/deadlineEndsAt"));
            actual.put("received", detail.at("/case/received"));
            actual.put("delivered", detail.at("/case/delivered"));
            actual.put("financials", detail.get("financials"));
            actual.put("counts", detail.get("counts"));

            List<Map<String, Object>> donors = new ArrayList<>();
            get("/api/cases/" + code + "/donors", Map.of("pageSize", "48")).ok().json().get("items").forEach(donor -> {
                Map<String, Object> listing = new LinkedHashMap<>();
                listing.put("id", donor.get("id"));
                listing.put("name", donor.get("name"));
                listing.put("amount", donor.get("amount"));
                listing.put("hasAvatar", !donor.get("avatarUrl").isNull());
                donors.add(listing);
            });
            actual.put("donors", donors);

            Map<String, Object> corrected = new LinkedHashMap<>();
            long allocated = 0;
            long delivered = 0;
            for (JsonNode ledgerEntry : get("/api/cases/" + code + "/ledger", Map.of("pageSize", "48")).ok().json().get("items")) {
                allocated += ledgerEntry.get("allocatedDelta").asLong();
                delivered += ledgerEntry.get("deliveredDelta").asLong();
                assertThat(ledgerEntry.get("netAmount").asLong())
                        .isEqualTo(ledgerEntry.get("amount").asLong() - ledgerEntry.get("correctedAmount").asLong());
                if (!ledgerEntry.get("correctedBy").isEmpty()) {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("correctedBy", ledgerEntry.get("correctedBy"));
                    summary.put("correctedAmount", ledgerEntry.get("correctedAmount"));
                    summary.put("netAmount", ledgerEntry.get("netAmount"));
                    corrected.put(ledgerEntry.get("id").asText(), summary);
                }
            }
            actual.put("corrected", corrected);

            assertThat(Json.canonical(Json.tree(actual))).as(code).isEqualTo(Json.canonical(entry.getValue()));
            // Invariants 1 and 2 of financial-terms.md: the ledger adds up to the totals shown.
            assertThat(allocated).as(code + " allocated").isEqualTo(detail.at("/case/received").asLong());
            assertThat(delivered).as(code + " delivered").isEqualTo(detail.at("/case/delivered").asLong());
        });
    }

    @Test
    void directoryQueriesReturnTheExpectedCasesInOrder() {
        expected.get("directory").fields().forEachRemaining(entry -> {
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : entry.getKey().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            query.put("pageSize", "48");
            List<String> wanted = new ArrayList<>();
            entry.getValue().forEach(code -> wanted.add(code.asText()));
            assertThat(get("/api/cases", query).ok().codes()).as(entry.getKey()).isEqualTo(wanted);
        });
    }

    @Test
    void platformReconciles() {
        long received = 0;
        long delivered = 0;
        long transfersIn = 0;
        long transfersOut = 0;
        for (String code : List.of("FUND", "HELP-901", "HELP-903", "HELP-904")) {
            JsonNode detail = get("/api/cases/" + code).ok().json();
            JsonNode f = detail.get("financials");
            received += detail.at("/case/received").asLong();
            delivered += detail.at("/case/delivered").asLong();
            transfersIn += f.get("transfersIn").asLong();
            transfersOut += f.get("transfersOut").asLong();
            assertThat(f.get("allocated").asLong())
                    .isEqualTo(f.get("externalDonations").asLong() + f.get("transfersIn").asLong() - f.get("transfersOut").asLong());
            assertThat(f.get("available").asLong()).isEqualTo(f.get("allocated").asLong() - f.get("delivered").asLong());
        }
        JsonNode stats = get("/api/stats").ok().json();
        // Invariants 5 to 7: public cases alone add up to the platform, and transfers cancel out.
        assertThat(received).isEqualTo(stats.get("received").asLong());
        assertThat(delivered).isEqualTo(stats.get("delivered").asLong());
        assertThat(transfersIn).isEqualTo(transfersOut);
        // The general fund allocates by transfer and never delivers to a beneficiary itself.
        JsonNode fund = get("/api/cases/FUND").ok().json();
        assertThat(fund.at("/financials/delivered").asLong()).isZero();
        assertThat(fund.at("/financials/transfersOut").asLong()).isEqualTo(10000);
    }

    @Test
    void aShortfallIsShownNotHidden() {
        JsonNode detail = get("/api/cases/HELP-903").ok().json();
        assertThat(detail.at("/financials/available").asLong()).isEqualTo(-1000);
        assertThat(detail.at("/financials/hasShortfall").asBoolean()).isTrue();
        assertThat(detail.at("/case/received").asLong()).isLessThan(detail.at("/case/delivered").asLong());
    }

    @Test
    void correctionsAndTransfersAreVisibleInTheLedger() {
        JsonNode ledger = get("/api/cases/HELP-901/ledger", Map.of("pageSize", "48")).ok().json();
        assertThat(ledger.get("totalItems").asInt()).isEqualTo(8);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        ledger.get("items").forEach(item -> byId.put(item.get("id").asText(), item));
        assertThat(byId.keySet()).containsExactly("L11", "L09", "L08", "L07", "L06", "L04", "L03", "L02");
        // Two partial refunds against one donation.
        assertThat(byId.get("L03").get("correctedBy").toString()).isEqualTo("[\"L08\",\"L09\"]");
        assertThat(byId.get("L03").get("netAmount").asLong()).isEqualTo(3500);
        assertThat(byId.get("L08").get("corrects").asText()).isEqualTo("L03");
        assertThat(byId.get("L08").get("allocatedDelta").asLong()).isEqualTo(-4000);
        // A partial transfer reversal names the other end.
        assertThat(byId.get("L11").get("counterpartCaseCode").asText()).isEqualTo("FUND");
        assertThat(byId.get("L06").get("counterpartCaseCode").asText()).isEqualTo("FUND");
        assertThat(byId.get("L07").get("counterpartCaseCode").isNull()).isTrue();
        assertThat(byId.get("L07").get("deliveredDelta").asLong()).isEqualTo(30000);
    }

    @Test
    void donorPrivacy() {
        JsonNode donors = get("/api/cases/HELP-901/donors").ok().json().get("items");
        // Named without picture consent, anonymous with consent (never shown), hidden (not listed).
        assertThat(donors).hasSize(2);
        assertThat(donors.get(0).get("id").asText()).isEqualTo("D3");
        assertThat(donors.get(0).get("name").isNull()).isTrue();
        assertThat(donors.get(0).get("avatarUrl").isNull()).isTrue();
        assertThat(donors.get(0).get("amount").asLong()).isEqualTo(3500);
        assertThat(donors.get(1).get("name").asText()).isEqualTo("Sample Donor Two");
        assertThat(donors.get(1).get("avatarUrl").isNull()).isTrue();
        // Named with consent: the picture is a same-origin public path.
        JsonNode fundDonor = get("/api/cases/FUND/donors").ok().json().at("/items/0");
        assertThat(fundDonor.get("avatarUrl").asText()).matches("/media/public/[A-Za-z0-9_-]{22}/thumb\\.webp");
        // The hidden donation is still counted in the totals.
        assertThat(get("/api/cases/HELP-901").ok().json().at("/financials/externalDonations").asLong()).isEqualTo(28500);
        // An author who did not agree to show a picture has none, although one is on file.
        assertThat(get("/api/cases/HELP-901/updates").ok().json().at("/items/0/authorAvatarUrl").isNull()).isTrue();
    }

    @Test
    void localizationAndPerFieldFallback() {
        Response french = get("/api/cases/HELP-901", Map.of(), "fr-CA, en;q=0.5");
        assertThat(french.raw().getHeader("Content-Language")).isEqualTo("fr");
        assertThat(french.raw().getHeader("Vary")).contains("Accept-Language");
        JsonNode detail = french.ok().json();
        assertThat(detail.at("/case/title").asText()).isEqualTo("Des fournitures pour la rentrée");
        assertThat(detail.at("/case/titleLanguage").asText()).isEqualTo("fr");
        assertThat(detail.at("/case/storyLanguage").asText()).isEqualTo("fr");
        assertThat(detail.at("/case/photoAlt").asText()).isEqualTo("Illustration symbolique d'un cartable");
        assertThat(detail.at("/case/photoAltLanguage").asText()).isEqualTo("fr");
        // Never translated.
        assertThat(detail.at("/case/publicName").asText()).isEqualTo("Sample household A");
        // Written in English only: returned in English and labelled as such.
        assertThat(detail.at("/updates/0/text").asText()).isEqualTo("Sample update written in English only.");
        assertThat(detail.at("/updates/0/textLanguage").asText()).isEqualTo("en");
        assertThat(detail.at("/photos/0/altLanguage").asText()).isEqualTo("en");
        // Ledger labels follow the response language.
        assertThat(detail.at("/ledger/1/label").asText()).isEqualTo("Remboursement au donateur");

        Response english = get("/api/cases/HELP-901", Map.of(), "de-DE");
        assertThat(english.raw().getHeader("Content-Language")).isEqualTo("en");
        assertThat(english.json().at("/case/title").asText()).isEqualTo("School supplies for a new term");
        assertThat(get("/api/cases/HELP-901").raw().getHeader("Content-Language")).isEqualTo("en");
        assertThat(get("/api/cases", Map.of(), "fr").ok().json().at("/items/0/title").asText()).isEqualTo("Des courses pour un mois");
    }

    @Test
    void searchIsNormalizedTheSameWayOnBothSides() {
        assertThat(search("rentree")).containsExactly("HELP-901");
        assertThat(search("RENTRÉE")).containsExactly("HELP-901");
        assertThat(search("rentrée")).containsExactly("HELP-901");
        assertThat(search("  help-90 \t")).containsExactly("HELP-904", "HELP-901");
        assertThat(search("household d")).containsExactly("HELP-904");
        assertThat(search("   ")).containsExactly("HELP-904", "HELP-901");
        // Category labels are not searched, and each field is tested on its own.
        assertThat(search("education")).isEmpty();
        assertThat(search("term Sample")).isEmpty();
        // Characters that are special in SQL patterns are plain text.
        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
        assertThat(search("' or 1=1 --")).isEmpty();
    }

    private List<String> search(String q) {
        return valid("CasePage", get("/api/cases", Map.of("q", q))).codes();
    }

    @Test
    void searchLengthLimits() {
        assertThat(get("/api/cases", Map.of("q", "a".repeat(80))).status()).isEqualTo(200);
        assertThat(get("/api/cases", Map.of("q", "😀".repeat(80))).status()).isEqualTo(200);
        assertFieldError(get("/api/cases", Map.of("q", "a".repeat(81))), "q", "too_long");
        assertFieldError(get("/api/cases", Map.of("q", " ".repeat(201))), "q", "too_long");
    }

    @Test
    void categoriesFilterIncludingInactiveAndUnknown() {
        assertThat(get("/api/cases", Map.of("category", "food")).ok().codes()).containsExactly("HELP-904");
        // An inactive category still filters, so old links keep working.
        assertThat(get("/api/cases", Map.of("category", "transport", "status", "completed")).ok().codes()).containsExactly("HELP-903");
        assertThat(get("/api/cases", Map.of("category", "medical")).ok().codes()).isEmpty();
        assertFieldError(get("/api/cases", Map.of("category", "sports")), "category", "invalid_value");
        assertFieldError(get("/api/cases", Map.of("category", "Not An Id")), "category", "invalid_value");
    }

    @Test
    void paginationBoundaries() {
        JsonNode first = valid("CasePage", get("/api/cases", Map.of("pageSize", "1"))).json();
        assertThat(first.get("totalItems").asInt()).isEqualTo(2);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("items")).hasSize(1);

        JsonNode beyond = valid("CasePage", get("/api/cases", Map.of("pageSize", "1", "page", "9"))).json();
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("totalItems").asInt()).isEqualTo(2);
        assertThat(beyond.get("page").asInt()).isEqualTo(9);

        JsonNode none = valid("CasePage", get("/api/cases", Map.of("q", "nothing matches this"))).json();
        assertThat(none.get("totalItems").asInt()).isZero();
        assertThat(none.get("totalPages").asInt()).isEqualTo(1);

        JsonNode ledgerPage = valid("LedgerEntryPage", get("/api/cases/HELP-901/ledger", Map.of("pageSize", "3", "page", "3"))).json();
        assertThat(ledgerPage.get("items")).hasSize(2);
        assertThat(ledgerPage.get("totalPages").asInt()).isEqualTo(3);
        assertThat(valid("LedgerEntryPage", get("/api/cases/HELP-904/ledger")).json().get("totalPages").asInt()).isEqualTo(1);

        assertFieldError(get("/api/cases", Map.of("pageSize", "0")), "pageSize", "out_of_range");
        assertFieldError(get("/api/cases", Map.of("pageSize", "49")), "pageSize", "out_of_range");
        assertFieldError(get("/api/cases", Map.of("page", "0")), "page", "out_of_range");
        assertFieldError(get("/api/cases", Map.of("page", "abc")), "page", "invalid_type");
        assertFieldError(get("/api/cases/HELP-901/donors", Map.of("pageSize", "100")), "pageSize", "out_of_range");
        assertFieldError(get("/api/cases", Map.of("status", "draft")), "status", "invalid_value");
        assertFieldError(get("/api/cases", Map.of("sort", "random")), "sort", "invalid_value");
    }

    @Test
    void caseCodesAreCaseInsensitiveAndDraftsDoNotExist() {
        assertThat(get("/api/cases/help-901").ok().json().at("/case/code").asText()).isEqualTo("HELP-901");
        assertThat(get("/api/cases/fund").ok().json().at("/case/kind").asText()).isEqualTo("fund");
        assertThat(get("/api/cases/Help-901/ledger").ok().json().get("totalItems").asInt()).isEqualTo(8);
        // A draft, an unknown code and a malformed code give the same answer.
        String draft = get("/api/cases/HELP-902").json().at("/error/message").asText();
        assertThat(error(get("/api/cases/HELP-999"), 404, "case_not_found").get("message").asText()).isEqualTo(draft);
        error(get("/api/cases/not%20a%20code"), 404, "case_not_found");
        error(get("/api/cases/HELP-902/donors"), 404, "case_not_found");
        // The fund is read by its code and is never in the directory.
        assertThat(get("/api/cases", Map.of("pageSize", "48")).codes()).doesNotContain("FUND", "HELP-902");
        assertThat(get("/api/cases", Map.of("status", "completed", "pageSize", "48")).codes()).doesNotContain("FUND", "HELP-902");
    }

    @Test
    void onlyTheApprovedGetEndpointsExist() {
        error(get("/api/nothing-here"), 404, "not_found");
        error(get("/api/cases/HELP-901/private"), 404, "not_found");
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String path : List.of("/api/cases", "/api/cases/HELP-901", "/api/cases/HELP-901/ledger", "/api/site", "/api/stats")) {
                Response response = perform(MockMvcRequestBuilders.request(method, path).contentType("application/json").content("{}"));
                // Since contract 0.3.0 a known path with an unsupported method is 405, replacing the 404 of milestone 0B.
                error(response, 405, "method_not_allowed");
                assertThat(response.raw().getHeader("Allow")).isEqualTo("GET, HEAD");
            }
        }
        assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isEqualTo(14);
    }

    @Test
    void acceptingDonationsFollowsStatusPauseAndDeadline() {
        assertThat(accepting("HELP-901")).isTrue();
        assertThat(accepting("FUND")).isTrue();
        // Delivered, and past its deadline.
        assertThat(accepting("HELP-903")).isFalse();
        // Paused by the administrator while still active.
        jdbc.sql("update aid_case set accepts_donations = false where code = 'HELP-901'").update();
        assertThat(accepting("HELP-901")).isFalse();
        jdbc.sql("update aid_case set accepts_donations = true, status = 'funded' where code = 'HELP-901'").update();
        // A funded case keeps accepting gifts until paused or past its deadline.
        assertThat(accepting("HELP-901")).isTrue();
        // An active case whose cutoff is exactly now no longer accepts: the end is exclusive.
        jdbc.sql("update aid_case set deadline_ends_at = timestamptz '2026-11-24T12:00:00Z' where code = 'HELP-904'").update();
        assertThat(accepting("HELP-904")).isFalse();
        jdbc.sql("update aid_case set deadline_ends_at = timestamptz '2026-11-24T12:00:01Z' where code = 'HELP-904'").update();
        assertThat(accepting("HELP-904")).isTrue();
    }

    private boolean accepting(String code) {
        return get("/api/cases/" + code).ok().json().at("/case/acceptingDonations").asBoolean();
    }

    @Test
    void changingTheOrganizationTimezoneDoesNotMoveExistingDeadlines() {
        JsonNode before = get("/api/cases/HELP-901").ok().json().get("case");
        jdbc.sql("update organization_settings set timezone = 'Europe/Paris'").update();
        assertThat(get("/api/site").ok().json().get("timezone").asText()).isEqualTo("Europe/Paris");
        JsonNode after = get("/api/cases/HELP-901").ok().json().get("case");
        assertThat(after.get("deadline").asText()).isEqualTo("2027-01-18").isEqualTo(before.get("deadline").asText());
        assertThat(after.get("deadlineTimezone").asText()).isEqualTo("America/Chicago");
        assertThat(after.get("deadlineEndsAt").asText()).isEqualTo("2027-01-19T06:00:00Z");
        // The deadline that falls on a daylight saving change.
        assertThat(get("/api/cases/HELP-904").ok().json().at("/case/deadlineEndsAt").asText()).isEqualTo("2027-03-15T05:00:00Z");
    }

    @Test
    void unpublishedPicturesAreNeverReferenced() {
        JsonNode detail = get("/api/cases/HELP-901").ok().json();
        assertThat(detail.at("/case/photoUrl").asText()).matches("/media/public/[A-Za-z0-9_-]{22}/display\\.webp");
        assertThat(detail.at("/counts/photos").asInt()).isEqualTo(1);
        // Withdraw both pictures: the case stays, the pictures and their descriptions go.
        jdbc.sql("update media_asset set published = false where kind in ('case_cover', 'case_gallery')").update();
        JsonNode withdrawn = valid("CaseDetail", get("/api/cases/HELP-901")).json();
        assertThat(withdrawn.at("/case/photoUrl").isNull()).isTrue();
        assertThat(withdrawn.at("/case/photoThumbnailUrl").isNull()).isTrue();
        assertThat(withdrawn.at("/case/photoAlt").isNull()).isTrue();
        assertThat(withdrawn.at("/case/photoAltLanguage").isNull()).isTrue();
        assertThat(withdrawn.get("photos")).isEmpty();
        assertThat(withdrawn.at("/counts/photos").asInt()).isZero();
    }

    @Test
    void developmentPictureRouteServesOnlyPublishedTokens() {
        String published = jdbc.sql("select token from media_asset where published and kind = 'case_cover'").query(String.class).single();
        String unpublished = jdbc.sql("select token from media_asset where not published limit 1").query(String.class).single();
        // No picture files are in the repository yet, so a published token is 404 as well: the
        // point here is that nothing outside the configured folder, and nothing unpublished, is served.
        assertThat(get("/media/public/" + unpublished + "/display.webp").status()).isEqualTo(404);
        assertThat(get("/media/public/" + published + "/display.webp").status()).isIn(200, 404);
        assertThat(get("/media/public/" + published + "/..%2F..%2Fexample.json").status()).isIn(400, 404);
        assertThat(get("/media/private/" + published + "/display.webp").status()).isEqualTo(404);
    }

    @Test
    void responsesCanBeRevalidated() {
        Response first = get("/api/stats").ok();
        assertThat(first.raw().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(first.raw().getHeader("X-Request-Id")).matches("[0-9a-f]{16}");
        String etag = first.raw().getHeader("ETag");
        assertThat(etag).isNotBlank();
        Response second = perform(request("/api/stats", Map.of(), null).header("If-None-Match", etag));
        assertThat(second.status()).isEqualTo(304);
    }
}
