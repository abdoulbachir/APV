package org.apvgakasinay.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The approved contract, read from contract/openapi.yaml. Responses of the real application
 * are validated against its schemas, which are closed: an extra property is a failure.
 */
public final class Contract {

    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final Contract INSTANCE = new Contract();

    private final JsonNode document;
    private final JsonNode definitions;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    private Contract() {
        try (Reader reader = Files.newBufferedReader(Repo.contract("openapi.yaml"), StandardCharsets.UTF_8)) {
            document = Json.tree(new Yaml().load(reader));
        } catch (IOException unreadable) {
            throw new AssertionError("cannot read the contract", unreadable);
        }
        definitions = Json.parse(document.at("/components/schemas").toString().replace("#/components/schemas/", "#/$defs/"));
    }

    public static Contract get() {
        return INSTANCE;
    }

    public String version() {
        return document.at("/info/version").asText();
    }

    /** Returns the violations of a response body against a named schema. Empty means valid. */
    public List<String> violations(String schemaName, String body) {
        JsonSchema schema = schemas.computeIfAbsent(schemaName, name -> {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("$schema", DIALECT);
            root.put("$ref", "#/$defs/" + name);
            root.set("$defs", definitions);
            return factory.getSchema(root);
        });
        return schema.validate(Json.parse(body)).stream().map(Object::toString).sorted().toList();
    }

    /** The example written in the contract for the 200 response of an operation. */
    public JsonNode example(String operationId) {
        for (JsonNode pathItem : document.get("paths")) {
            JsonNode operation = pathItem.get("get");
            if (operation != null && operationId.equals(operation.path("operationId").asText())) {
                return operation.at("/responses/200/content/application~1json/example");
            }
        }
        throw new AssertionError("no operation " + operationId);
    }
}
