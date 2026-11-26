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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** Scenario 13.7: what the development seed creates on a disposable database, and what it never does. */
    @Test
    void a13_7_theDevelopmentSeedCreatesThreeFictionalAccountsWithGeneratedPasswords() throws Exception {
        java.util.List<String> emails = jdbc.sql("select email from account where email like '%@example.test' order by email").query(String.class).list();
        assertThat(emails).containsExactly("dev.admin@example.test", "dev.donor@example.test", "dev.owner@example.test");
        // Each has an Argon2id hash of a password generated at this start; staff have an active authenticator.
        assertThat(jdbc.sql("select count(*) from account_password p join account a on a.id = p.account_id where a.email like '%@example.test' and p.password_hash like '{argon2id}$argon2id$%'")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from account_totp t join account a on a.id = t.account_id where a.email like '%@example.test' and t.state = 'active'")
                .query(Long.class).single()).isEqualTo(2);
        // The seed is development data: it never marks the site bootstrapped and never makes the database real.
        assertThat(jdbc.sql("select bootstrapped or database_real from identity_guard").query(Boolean.class).single()).isFalse();
        // No password is committed anywhere: a guessable one does not work.
        HttpResponse<String> csrf = call("GET", "/api/auth/csrf");
        String cookie = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String token = Json.parse(csrf.body()).get("token").asText();
        for (String guess : new String[] {"password", "dev", "dev.donor@example.test", "changeme-changeme"}) {
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json").header("Cookie", cookie).header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"dev.donor@example.test\",\"password\":\"" + guess + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(401);
        }
    }
}
