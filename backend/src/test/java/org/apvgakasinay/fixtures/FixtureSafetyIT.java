package org.apvgakasinay.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.support.PostgresIntegration;
import org.junit.jupiter.api.Test;

/** What the destructive fixture loader refuses to do, and what it must never publish. */
class FixtureSafetyIT extends PostgresIntegration {

    @Test
    void aDatabaseHoldingRealDataIsNeverReplaced() {
        loadExample();
        assertThat(jdbc.sql("select data_origin from organization_settings").query(String.class).single()).isEqualTo("fixture");
        String before = get("/api/stats").ok().body();
        jdbc.sql("update organization_settings set data_origin = 'real'").update();
        try {
            assertThatThrownBy(this::loadExample).isInstanceOf(FixtureException.class).hasMessageContaining("did not come from a fixture");
            assertThat(get("/api/stats").ok().body()).isEqualTo(before);
            assertThat(jdbc.sql("select count(*) from ledger_entry").query(Long.class).single()).isEqualTo(14);
        } finally {
            jdbc.sql("update organization_settings set data_origin = 'fixture'").update();
        }
        loadExample();
    }

    @Test
    void anUnsupportedFixtureVersionIsRefusedBeforeAnythingIsDeleted() {
        loadExample();
        Map<String, Object> old = exampleFixture();
        old.put("version", 1);
        assertThatThrownBy(() -> importer.replaceAllData(old)).isInstanceOf(FixtureException.class).hasMessageContaining("version");
        assertThat(jdbc.sql("select count(*) from aid_case").query(Long.class).single()).isEqualTo(5);
    }

    /**
     * Review finding B: an author's picture on an update of a draft case must stay unpublished
     * even when the author agreed to show it. The same file used on a public case is published
     * there, under a different token.
     */
    @Test
    void anAuthorPictureOnADraftCaseIsNotPublishedByConsentAlone() {
        Map<String, Object> fixture = exampleFixture();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updates = (List<Map<String, Object>>) fixture.get("updates");
        updates.add(update("U-DRAFT", "HELP-902", "images/hope-window.webp"));
        importer.replaceAllData(fixture);

        assertThat(jdbc.sql("select count(*) from media_asset where storage_key = 'images/hope-window.webp' and published")
                .query(Long.class).single()).isZero();
        String draftToken = jdbc.sql("""
                select m.token from case_update u join media_asset m on m.id = u.author_avatar_media_id where u.public_id = 'U-DRAFT'
                """).query(String.class).single();
        // The file exists in the picture folder, so only the publication flag keeps it private.
        assertThat(get("/media/public/" + draftToken + "/thumb.webp").status()).isEqualTo(404);
        error(get("/api/cases/HELP-902/updates"), 404, "case_not_found");

        // Legitimately public elsewhere: the same picture on an update of a public case.
        updates.add(update("U-PUBLIC", "HELP-901", "images/hope-window.webp"));
        importer.replaceAllData(fixture);
        String avatarUrl = get("/api/cases/HELP-901/updates").ok().json().at("/items/0/authorAvatarUrl").asText();
        assertThat(avatarUrl).startsWith("/media/public/").doesNotContain(draftToken);
        assertThat(get(avatarUrl).status()).isEqualTo(200);
        String stillPrivate = jdbc.sql("""
                select m.token from case_update u join media_asset m on m.id = u.author_avatar_media_id where u.public_id = 'U-DRAFT'
                """).query(String.class).single();
        assertThat(get("/media/public/" + stillPrivate + "/thumb.webp").status()).isEqualTo(404);
        loadExample();
    }

    private static Map<String, Object> update(String id, String caseCode, String avatarFile) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("id", id);
        update.put("caseCode", caseCode);
        update.put("text", Map.of("en", "Sample update.", "fr", "Exemple de nouvelle."));
        update.put("date", "2026-11-22T10:00:00Z");
        update.put("author", "Sample Admin");
        update.put("authorAvatarConsent", true);
        update.put("authorAvatarFile", avatarFile);
        update.put("photo", null);
        return update;
    }
}
