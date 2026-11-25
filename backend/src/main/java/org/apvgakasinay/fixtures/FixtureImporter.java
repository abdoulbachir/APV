package org.apvgakasinay.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.DatabasePurpose;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.Deadlines;
import org.apvgakasinay.shared.SearchText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads a shared fixture file (contract/fixtures/fixture.schema.json, version 2) into the
 * database, DELETING everything that was there. Development and tests only: this bean does
 * not exist outside the dev and test profiles, so production can never load fictional data.
 *
 * It is generic: it knows the format, not any particular case or amount. It stores inputs
 * only. Every total is computed later by the read queries from the ledger rows.
 */
@Component
@Profile({"dev", "test"})
public class FixtureImporter {

    private static final int SUPPORTED_VERSION = 2;

    private final JdbcClient jdbc;
    private final AppClock clock;
    private final JsonMapper json;
    private final String databaseUrl;
    private final DatabasePurpose purpose;
    private final String allowedHosts;

    public FixtureImporter(
            JdbcClient jdbc,
            AppClock clock,
            JsonMapper json,
            DatabasePurpose purpose,
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${apv.fixtures.allowed-hosts:" + DatabaseTarget.LOCAL_HOSTS + "}") String allowedHosts) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.databaseUrl = databaseUrl;
        this.purpose = purpose;
        this.allowedHosts = allowedHosts;
    }

    /**
     * DESTRUCTIVE: the import deletes case, ledger and picture data first. It never touches
     * accounts, credentials, sessions, rate limits or the audit log. Three independent checks
     * stand in the way of doing that to anything but a disposable development or test database.
     *
     * 0. The database must have been explicitly marked disposable when it was created, and never
     *    used by the operator command (identity-and-security.md, 10.2).
     * 1. The database must be on this machine (or on a host explicitly listed in
     *    apv.fixtures.allowed-hosts, for a container network).
     * 2. The database must be empty, or its content must itself have come from a fixture. A
     *    database that ever held real cases is marked "real" and is never replaced.
     */
    private void refuseUnlessDisposable() {
        if (!DatabaseTarget.isAllowed(databaseUrl, allowedHosts)) {
            throw new FixtureException("refusing to load a fixture: the database at " + DatabaseTarget.host(databaseUrl)
                    + " is not on this machine and is not listed in apv.fixtures.allowed-hosts");
        }
        if (!purpose.disposable()) {
            throw new FixtureException("refusing to load a fixture: this database was not explicitly marked disposable when it was created, "
                    + "or the operator command has been used on it. An unmarked database is refused even when it is empty.");
        }
        String origin = jdbc.sql("select data_origin from organization_settings").query(String.class).single();
        long cases = jdbc.sql("select count(*) from aid_case").query(Long.class).single();
        if (cases > 0 && !"fixture".equals(origin)) {
            throw new FixtureException("refusing to load a fixture: this database holds " + cases
                    + " cases that did not come from a fixture, and loading would delete them");
        }
    }

    @Transactional
    public void replaceAllData(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            replaceAllData(json.readValue(in, Map.class));
        } catch (IOException unreadable) {
            throw new FixtureException("cannot read fixture file " + file + ": " + unreadable.getMessage());
        }
    }

    @Transactional
    public void replaceAllData(Map<String, Object> fixture) {
        if (!Integer.valueOf(SUPPORTED_VERSION).equals(fixture.get("version"))) {
            throw new FixtureException("unsupported fixture version " + fixture.get("version") + ", expected " + SUPPORTED_VERSION);
        }
        refuseUnlessDisposable();
        Loader loader = new Loader();
        loader.clear();
        loader.settings(fixture);
        loader.categories(list(fixture, "categories"));
        loader.labels(map(fixture, "ledgerLabels"));
        loader.cases(list(fixture, "cases"));
        loader.updates(list(fixture, "updates"));
        loader.ledger(list(fixture, "ledger"));
        clock.pin(Instant.parse(text(fixture, "clock")));
    }

    /** One import. Holds the identifiers created so far. */
    private final class Loader {

        private final Map<String, Long> caseIds = new HashMap<>();
        private final Map<String, Boolean> casePublic = new HashMap<>();
        private final Map<String, Long> entryIds = new HashMap<>();
        private final Map<String, Long> mediaIds = new HashMap<>();

        void clear() {
            jdbc.sql("""
                    truncate table donation_listing, ledger_entry, case_update, case_photo, case_private,
                                   aid_case, media_asset, category restart identity cascade
                    """).update();
        }

        void settings(Map<String, Object> fixture) {
            jdbc.sql("update organization_settings set timezone = :timezone, data_origin = 'fixture'").param("timezone", zone(text(fixture, "timezone")).getId()).update();
        }

        void categories(List<Map<String, Object>> categories) {
            for (Map<String, Object> category : categories) {
                Map<String, Object> labels = map(category, "labels");
                jdbc.sql("insert into category (id, label_en, label_fr, position, active) values (:id, :en, :fr, :position, :active)")
                        .param("id", text(category, "id"))
                        .param("en", text(labels, "en"))
                        .param("fr", text(labels, "fr"))
                        .param("position", category.get("position"))
                        .param("active", category.get("active"))
                        .update();
            }
        }

        void labels(Map<String, Object> labels) {
            labels.forEach((kind, value) -> {
                Map<String, Object> label = cast(value);
                int updated = jdbc.sql("update ledger_kind set label_en = :en, label_fr = :fr where kind = :kind")
                        .param("en", text(label, "en")).param("fr", text(label, "fr")).param("kind", kind).update();
                if (updated != 1) {
                    throw new FixtureException("unknown ledger kind in ledgerLabels: " + kind);
                }
            });
        }

        void cases(List<Map<String, Object>> cases) {
            for (Map<String, Object> c : cases) {
                String code = text(c, "code");
                boolean isPublic = !"draft".equals(text(c, "status"));
                Map<String, Object> title = map(c, "title");
                Map<String, Object> story = map(c, "story");
                Map<String, Object> cover = optionalMap(c, "cover");
                Map<String, Object> coverAlt = cover == null ? Map.of() : map(cover, "alt");
                Instant createdAt = Instant.parse(text(c, "createdAt"));

                String deadline = optionalText(c, "deadline");
                String deadlineZone = optionalText(c, "deadlineTimezone");
                if ((deadline == null) != (deadlineZone == null)) {
                    throw new FixtureException(code + ": deadline and deadlineTimezone must be set together");
                }
                LocalDate deadlineDate = deadline == null ? null : LocalDate.parse(deadline);
                Instant cutoff = deadline == null ? null : Deadlines.cutoff(deadlineDate, zone(deadlineZone));

                long id = jdbc.sql("""
                                insert into aid_case (code, kind, status, accepts_donations, category_id, featured_rank, public_name,
                                                      title_en, title_fr, story_en, story_fr, goal_cents,
                                                      deadline_date, deadline_timezone, deadline_ends_at, published_at,
                                                      cover_media_id, cover_alt_en, cover_alt_fr,
                                                      search_title_en, search_title_fr, search_public_name, search_code)
                                values (:code, :kind, :status, :accepts, :category, :rank, :publicName,
                                        :titleEn, :titleFr, :storyEn, :storyFr, :goal,
                                        :deadlineDate, :deadlineZone, :cutoff, :publishedAt,
                                        :cover, :coverAltEn, :coverAltFr,
                                        :searchTitleEn, :searchTitleFr, :searchName, :searchCode)
                                returning id
                                """)
                        .param("code", code)
                        .param("kind", text(c, "kind"))
                        .param("status", text(c, "status"))
                        .param("accepts", c.get("acceptsDonations"))
                        .param("category", c.get("category"))
                        .param("rank", c.get("featuredRank"))
                        .param("publicName", text(c, "publicName"))
                        .param("titleEn", title.get("en"))
                        .param("titleFr", title.get("fr"))
                        .param("storyEn", story.get("en"))
                        .param("storyFr", story.get("fr"))
                        .param("goal", c.get("goal"))
                        .param("deadlineDate", deadlineDate)
                        .param("deadlineZone", deadlineZone)
                        .param("cutoff", utc(cutoff))
                        .param("publishedAt", isPublic ? utc(createdAt) : null)
                        .param("cover", cover == null ? null : media(text(cover, "file"), "case_cover", isPublic))
                        .param("coverAltEn", coverAlt.get("en"))
                        .param("coverAltFr", coverAlt.get("fr"))
                        .param("searchTitleEn", SearchText.normalize((String) title.get("en")))
                        .param("searchTitleFr", SearchText.normalize((String) title.get("fr")))
                        .param("searchName", SearchText.normalize(text(c, "publicName")))
                        .param("searchCode", SearchText.normalize(code))
                        .query(Long.class)
                        .single();
                caseIds.put(code, id);
                casePublic.put(code, isPublic);

                Map<String, Object> secret = map(c, "private");
                jdbc.sql("insert into case_private (case_id, real_name, contact, notes) values (:id, :realName, :contact, :notes)")
                        .param("id", id)
                        .param("realName", text(secret, "realName"))
                        .param("contact", text(secret, "contact"))
                        .param("notes", text(secret, "notes"))
                        .update();

                List<Map<String, Object>> photos = list(c, "photos");
                List<Integer> positions = photos.stream().map(p -> (Integer) p.get("position")).sorted().toList();
                for (int i = 0; i < positions.size(); i++) {
                    if (positions.get(i) != i + 1) {
                        throw new FixtureException(code + ": gallery positions must be 1..n");
                    }
                }
                for (Map<String, Object> photo : photos) {
                    Map<String, Object> alt = map(photo, "alt");
                    jdbc.sql("""
                                    insert into case_photo (case_id, public_id, media_id, alt_en, alt_fr, position)
                                    values (:caseId, :publicId, :media, :altEn, :altFr, :position)
                                    """)
                            .param("caseId", id)
                            .param("publicId", text(photo, "id"))
                            .param("media", media(text(photo, "file"), "case_gallery", isPublic))
                            .param("altEn", alt.get("en"))
                            .param("altFr", alt.get("fr"))
                            .param("position", photo.get("position"))
                            .update();
                }
            }
        }

        void updates(List<Map<String, Object>> updates) {
            for (Map<String, Object> update : updates) {
                String caseCode = text(update, "caseCode");
                boolean isPublic = Boolean.TRUE.equals(casePublic.get(caseCode));
                Map<String, Object> body = map(update, "text");
                Map<String, Object> photo = optionalMap(update, "photo");
                Map<String, Object> photoAlt = photo == null ? Map.of() : map(photo, "alt");
                boolean consent = Boolean.TRUE.equals(update.get("authorAvatarConsent"));
                String avatarFile = optionalText(update, "authorAvatarFile");
                jdbc.sql("""
                                insert into case_update (public_id, case_id, text_en, text_fr, posted_at, author_name,
                                                         author_avatar_media_id, author_avatar_consent,
                                                         photo_media_id, photo_alt_en, photo_alt_fr)
                                values (:publicId, :caseId, :textEn, :textFr, :postedAt, :author,
                                        :avatar, :consent, :photo, :photoAltEn, :photoAltFr)
                                """)
                        .param("publicId", text(update, "id"))
                        .param("caseId", caseId(caseCode, "update " + text(update, "id")))
                        .param("textEn", body.get("en"))
                        .param("textFr", body.get("fr"))
                        .param("postedAt", utc(Instant.parse(text(update, "date"))))
                        .param("author", text(update, "author"))
                        // Consent alone is not enough: nothing attached to a draft case is public.
                        .param("avatar", avatarFile == null ? null : media(avatarFile, "avatar", consent && isPublic))
                        .param("consent", consent)
                        .param("photo", photo == null ? null : media(text(photo, "file"), "update_photo", isPublic))
                        .param("photoAltEn", photoAlt.get("en"))
                        .param("photoAltFr", photoAlt.get("fr"))
                        .update();
            }
        }

        /**
         * Entries are inserted in chronological order, the order they would have been recorded
         * in, so the database's own rules (correction limits, no overspending) see the same
         * history a real system would. Transfer pairs are checked by the database at commit.
         */
        void ledger(List<Map<String, Object>> entries) {
            List<Map<String, Object>> ordered = new ArrayList<>(entries);
            ordered.sort(Comparator.comparing((Map<String, Object> e) -> Instant.parse(text(e, "date"))).thenComparing(e -> text(e, "id")));
            for (Map<String, Object> entry : ordered) {
                String publicId = text(entry, "id");
                String kind = text(entry, "kind");
                String corrects = optionalText(entry, "corrects");
                Long correctsId = null;
                if (corrects != null) {
                    correctsId = entryIds.get(corrects);
                    if (correctsId == null) {
                        throw new FixtureException(publicId + ": corrects " + corrects + ", which does not exist or is not earlier");
                    }
                }
                Map<String, Object> donor = optionalMap(entry, "donor");
                if (kind.equals("donation") != (donor != null)) {
                    throw new FixtureException(publicId + ": a donor block belongs on donations and only there");
                }
                long id = jdbc.sql("""
                                insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at, corrects_id, pair_id)
                                values (:publicId, :caseId, :kind, :amount, :effectiveAt, :corrects, :pair)
                                returning id
                                """)
                        .param("publicId", publicId)
                        .param("caseId", caseId(text(entry, "caseCode"), "ledger entry " + publicId))
                        .param("kind", kind)
                        .param("amount", entry.get("amount"))
                        .param("effectiveAt", utc(Instant.parse(text(entry, "date"))))
                        .param("corrects", correctsId)
                        .param("pair", entry.get("pairId"))
                        .query(Long.class)
                        .single();
                entryIds.put(publicId, id);

                if (donor != null) {
                    boolean named = "named".equals(text(donor, "visibility"));
                    boolean consent = Boolean.TRUE.equals(donor.get("avatarConsent"));
                    String avatarFile = optionalText(donor, "avatarFile");
                    jdbc.sql("""
                                    insert into donation_listing (ledger_entry_id, public_id, visibility, donor_name, avatar_media_id, avatar_consent)
                                    values (:entry, :publicId, :visibility, :name, :avatar, :consent)
                                    """)
                            .param("entry", id)
                            .param("publicId", text(donor, "listingId"))
                            .param("visibility", text(donor, "visibility"))
                            .param("name", donor.get("name"))
                            .param("avatar", avatarFile == null ? null : media(avatarFile, "avatar", named && consent))
                            .param("consent", consent)
                            .update();
                }
            }
        }

        private long caseId(String code, String what) {
            Long id = caseIds.get(code);
            if (id == null) {
                throw new FixtureException(what + ": unknown case " + code);
            }
            return id;
        }

        /**
         * Registers a picture. A real server gives each picture a random token; fixtures need
         * repeatable ones, so the token is derived from the file name. A picture that must not
         * be public gets a different token and is stored unpublished.
         */
        private long media(String file, String kind, boolean published) {
            String key = (published ? "" : "unpublished:") + file;
            return mediaIds.computeIfAbsent(key, k -> jdbc
                    .sql("""
                            insert into media_asset (token, kind, format, storage_key, published)
                            values (:token, :kind, :format, :storageKey, :published)
                            returning id
                            """)
                    .param("token", token(k))
                    .param("kind", kind)
                    .param("format", format(file))
                    .param("storageKey", file)
                    .param("published", published)
                    .query(Long.class)
                    .single());
        }
    }

    private static String token(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String format(String file) {
        String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase();
        return extension.equals("jpeg") ? "jpg" : extension;
    }

    private static ZoneId zone(String name) {
        try {
            if (!name.contains("/")) {
                throw new IllegalArgumentException();
            }
            return ZoneId.of(name);
        } catch (RuntimeException unknown) {
            throw new FixtureException("unknown timezone: " + name);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text)) {
            throw new FixtureException("missing text property \"" + key + "\"");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> source, String key) {
        return (String) source.get(key);
    }

    private static Map<String, Object> map(Map<String, Object> source, String key) {
        Map<String, Object> value = optionalMap(source, key);
        if (value == null) {
            throw new FixtureException("missing object property \"" + key + "\"");
        }
        return value;
    }

    private static Map<String, Object> optionalMap(Map<String, Object> source, String key) {
        return cast(source.get(key));
    }

    private static List<Map<String, Object>> list(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?>)) {
            throw new FixtureException("missing list property \"" + key + "\"");
        }
        return cast(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
