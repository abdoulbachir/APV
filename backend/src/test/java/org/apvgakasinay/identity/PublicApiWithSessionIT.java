package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.apvgakasinay.fixtures.FixtureImporter;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario 13.2 over the full shared dataset: every public response is byte for byte the same
 * for a visitor, a signed-in donor, a signed-in super administrator and a pending sign-in.
 */
class PublicApiWithSessionIT extends IdentityIntegration {

    @Autowired
    FixtureImporter importer;

    @Test
    void a13_2_theWholePublicApiIsTheSameWithAndWithoutASession() {
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/dataset.json")));
        clock.pin(START);
        Browser visitor = browser();
        Browser donor = donor("sample.donor@example.org");
        Staff owner = staff("sample.owner@example.org", "super_admin");
        Browser pending = pending(owner);
        List<Browser> signedIn = List.of(donor, owner.browser(), pending);

        List<String> paths = new ArrayList<>(List.of("/api/site", "/api/stats", "/api/cases/FUND", "/api/cases/FUND/ledger?pageSize=48"));
        List<String> codes = new ArrayList<>();
        for (String status : List.of("active", "completed")) {
            for (int page = 1; ; page++) {
                String path = "/api/cases?status=" + status + "&pageSize=12&page=" + page;
                paths.add(path);
                JsonNode body = visitor.get(path).json();
                body.get("items").forEach(item -> codes.add(item.get("code").asText()));
                if (page >= body.get("totalPages").asInt()) {
                    break;
                }
            }
        }
        assertThat(codes.size()).as("every published case of the dataset").isGreaterThanOrEqualTo(202);
        paths.add("/api/cases?sort=closest&category=medical&q=a");
        for (String code : codes) {
            paths.add("/api/cases/" + code);
            paths.add("/api/cases/" + code + "/updates");
            paths.add("/api/cases/" + code + "/donors");
            paths.add("/api/cases/" + code + "/ledger");
        }
        int compared = 0;
        for (String path : paths) {
            Browser.Answer anonymous = visitor.get(path);
            assertThat(anonymous.status()).as(path).isEqualTo(200);
            assertThat(anonymous.headers().allValues("Set-Cookie")).as(path).isEmpty();
            for (Browser browser : signedIn) {
                Browser.Answer answer = browser.get(path);
                assertThat(answer.status()).as(path).isEqualTo(200);
                assertThat(answer.body()).as(path).isEqualTo(anonymous.body());
                assertThat(answer.header("ETag")).as(path).isEqualTo(anonymous.header("ETag"));
                assertThat(answer.header("Cache-Control")).as(path).isEqualTo("no-cache");
                assertThat(answer.headers().allValues("Set-Cookie")).as(path).isEmpty();
                compared++;
            }
        }
        assertThat(visitor.sessionId).as("a visitor reading everything got no session").isNull();
        // Reading public pages did not change any session either.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertError(pending.get("/api/me"), 401, "mfa_required");
        Evidence.write("public-api-with-session", String.format(
                "Full dataset: %d public paths over %d published cases, each compared for a donor, a super administrator and a pending sign-in%n"
                        + "against the same path with no cookie: %d comparisons, bodies and ETags identical, no Set-Cookie on any.%n",
                paths.size(), codes.size(), compared));
    }
}
