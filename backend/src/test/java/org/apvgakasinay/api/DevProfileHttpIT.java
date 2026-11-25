package org.apvgakasinay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apvgakasinay.support.Contract;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application the way a developer runs it: the dev profile loads the sample fixture at
 * start-up, and the endpoints are called over real HTTP on a real port, not through a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevProfileHttpIT {

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("apv.fixtures.path", () -> Repo.contract("fixtures/example.json").toString());
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> call(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSevenEndpointsAnswerOverHttpWithTheSampleDataLoadedAtStartup() throws Exception {
        String[][] endpoints = {
                {"/api/site", "Site"},
                {"/api/stats", "Stats"},
                {"/api/cases?status=active&sort=closest", "CasePage"},
                {"/api/cases/HELP-901", "CaseDetail"},
                {"/api/cases/HELP-901/updates", "CaseUpdatePage"},
                {"/api/cases/HELP-901/donors", "PublicDonorPage"},
                {"/api/cases/HELP-901/ledger?pageSize=5", "LedgerEntryPage"},
        };
        for (String[] endpoint : endpoints) {
            HttpResponse<String> response = call("GET", endpoint[0]);
            assertThat(response.statusCode()).as(endpoint[0]).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-cache");
            assertThat(response.headers().firstValue("ETag")).isPresent();
            assertThat(Contract.get().violations(endpoint[1], response.body())).as(endpoint[0]).isEmpty();
        }
        JsonNode stats = Json.parse(call("GET", "/api/stats").body());
        assertThat(stats.get("received").asLong()).isEqualTo(102500);
        assertThat(stats.get("asOf").asText()).isEqualTo("2026-11-24T12:00:00Z");
    }

    @Test
    void languageHeadersAndFrenchTextOverHttp() throws Exception {
        HttpResponse<String> response = call("GET", "/api/cases/HELP-901", "Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
        assertThat(response.headers().firstValue("Content-Language")).contains("fr");
        assertThat(response.headers().allValues("Vary")).anyMatch(value -> value.contains("Accept-Language"));
        assertThat(Json.parse(response.body()).at("/case/title").asText()).isEqualTo("Des fournitures pour la rentrée");
    }

    @Test
    void errorsAndUnknownRoutesOverHttp() throws Exception {
        HttpResponse<String> draft = call("GET", "/api/cases/HELP-902");
        assertThat(draft.statusCode()).isEqualTo(404);
        assertThat(Contract.get().violations("Error", draft.body())).isEmpty();
        assertThat(Json.parse(draft.body()).at("/error/code").asText()).isEqualTo("case_not_found");

        HttpResponse<String> invalid = call("GET", "/api/cases?pageSize=500");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(Contract.get().violations("Error", invalid.body())).isEmpty();

        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            HttpResponse<String> refused = call(method, "/api/cases/HELP-901");
            // A known path with an unsupported method: 405 since contract 0.3.0.
            assertThat(refused.statusCode()).as(method).isEqualTo(405);
            assertThat(Json.parse(refused.body()).at("/error/code").asText()).isEqualTo("method_not_allowed");
            assertThat(refused.headers().firstValue("Allow")).contains("GET, HEAD");
        }
        assertThat(call("GET", "/").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/actuator").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/cases/..%2F..%2Fsite").statusCode()).isIn(400, 404);
    }

    @Test
    void conditionalRequestsAnswerNotModified() throws Exception {
        String etag = call("GET", "/api/site").headers().firstValue("ETag").orElseThrow();
        assertThat(call("GET", "/api/site", "If-None-Match", etag).statusCode()).isEqualTo(304);
    }
}
