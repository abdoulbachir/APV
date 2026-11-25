package org.apvgakasinay.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.PostgresIntegration;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.Test;

/**
 * Every broken fixture of contract/fixtures/negative-cases.json must be refused: either by
 * the fixture format, or - the interesting part - by the importer and the database's own
 * constraints. After each refusal the database must still hold the valid data untouched.
 */
class NegativeFixturesIT extends PostgresIntegration {

    @Test
    void everyBrokenFixtureIsRefusedAndLeavesTheDataUntouched() {
        JsonSchema format = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Json.tree(Json.readMap(Repo.contract("fixtures/fixture.schema.json"))));
        assertThat(format.validate(Json.tree(exampleFixture()))).isEmpty();

        loadExample();
        String statsBefore = statsWithoutRequestNoise();

        List<String> refusedByFormat = new ArrayList<>();
        List<String> refusedByDatabase = new ArrayList<>();
        for (Map<String, Object> negative : Json.readList(Repo.contract("fixtures/negative-cases.json"))) {
            String name = (String) negative.get("name");
            Map<String, Object> broken = exampleFixture();
            for (Map<String, Object> operation : operations(negative)) {
                JsonPatch.apply(broken, operation);
            }
            if (!format.validate(Json.tree(broken)).isEmpty()) {
                refusedByFormat.add(name);
                continue;
            }
            assertThatThrownBy(() -> importer.replaceAllData(broken)).as(name).isInstanceOf(RuntimeException.class);
            refusedByDatabase.add(name);
            // The failed import rolled back completely.
            assertThat(statsWithoutRequestNoise()).as("data after: " + name).isEqualTo(statsBefore);
        }

        assertThat(refusedByFormat.size() + refusedByDatabase.size()).isEqualTo(45);
        assertThat(refusedByFormat).hasSize(8);
        assertThat(refusedByDatabase).hasSize(37).contains(
                "two partial refunds that together exceed the donation",
                "the same refund recorded twice",
                "transfer with only one side",
                "money recorded on a draft case",
                "disbursement larger than what the case holds",
                "new disbursement from a case that has a shortfall",
                "correction of a correction");
    }

    private String statsWithoutRequestNoise() {
        return get("/api/stats").ok().body() + get("/api/cases/HELP-901").ok().body() + get("/api/site").ok().body();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> negative) {
        return (List<Map<String, Object>>) negative.get("patch");
    }
}
