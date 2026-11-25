package org.apvgakasinay.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.fixtures.FixtureImporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base of every integration test: the whole application against a real PostgreSQL started by
 * Testcontainers, with the schema created by the Flyway migrations. One container serves all
 * test classes. There is no in-memory substitute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgresIntegration {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected FixtureImporter importer;

    @Autowired
    protected JdbcClient jdbc;

    protected final Contract contract = Contract.get();

    protected static Map<String, Object> exampleFixture() {
        return Json.readMap(Repo.contract("fixtures/example.json"));
    }

    protected void loadExample() {
        importer.replaceAllData(exampleFixture());
    }

    /** A response of the application. */
    public record Response(int status, String body, MockHttpServletResponse raw) {

        public JsonNode json() {
            return Json.parse(body);
        }

        public Response ok() {
            assertThat(status).as(body).isEqualTo(200);
            return this;
        }

        public List<String> codes() {
            List<String> codes = new ArrayList<>();
            json().get("items").forEach(item -> codes.add(item.get("code").asText()));
            return codes;
        }

        public List<String> ids() {
            List<String> ids = new ArrayList<>();
            json().get("items").forEach(item -> ids.add(item.get("id").asText()));
            return ids;
        }
    }

    protected Response get(String path) {
        return get(path, Map.of(), null);
    }

    protected Response get(String path, Map<String, String> query) {
        return get(path, query, null);
    }

    protected Response get(String path, Map<String, String> query, String acceptLanguage) {
        return perform(request(path, query, acceptLanguage));
    }

    protected MockHttpServletRequestBuilder request(String path, Map<String, String> query, String acceptLanguage) {
        MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        query.forEach(request::queryParam);
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return request;
    }

    protected Response perform(MockHttpServletRequestBuilder request) {
        try {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            return new Response(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8), response);
        } catch (Exception failure) {
            throw new AssertionError("request failed", failure);
        }
    }

    /** Asserts a 200 response that is valid against the named contract schema. */
    protected Response valid(String schemaName, Response response) {
        response.ok();
        assertThat(contract.violations(schemaName, response.body())).as("%s violations in %s", schemaName, response.body()).isEmpty();
        return response;
    }

    /** Asserts an error response with the contract's envelope. */
    protected JsonNode error(Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(contract.violations("Error", response.body())).as(response.body()).isEmpty();
        JsonNode error = response.json().get("error");
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("requestId").asText()).matches("[0-9a-f]{16}");
        return error;
    }

    protected void assertFieldError(Response response, String field, String fieldCode) {
        JsonNode fields = error(response, 400, "validation_error").get("fields");
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).get("name").asText()).isEqualTo(field);
        assertThat(fields.get(0).get("code").asText()).isEqualTo(fieldCode);
    }
}
