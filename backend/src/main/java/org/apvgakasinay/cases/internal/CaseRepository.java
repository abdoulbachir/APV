package org.apvgakasinay.cases.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apvgakasinay.cases.CasePhotoResponse;
import org.apvgakasinay.cases.CaseQuery;
import org.apvgakasinay.cases.CaseUpdateResponse;
import org.apvgakasinay.cases.CategoryResponse;
import org.apvgakasinay.media.MediaPath;
import org.apvgakasinay.shared.Language;
import org.apvgakasinay.shared.Localized;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of the cases module. Draft cases are excluded in every public read. Money comes from
 * the ledger module's {@code case_balance} view and pictures from the media module's
 * {@code public_media} view; this module never reads their tables.
 */
@Repository
public class CaseRepository {

    private static final String PUBLIC_CASES = """
            select c.id, c.code, c.kind, c.status, c.accepts_donations, c.category_id, c.featured_rank, c.public_name,
                   c.title_en, c.title_fr, c.story_en, c.story_fr, c.goal_cents,
                   c.deadline_date, c.deadline_timezone, c.deadline_ends_at, c.published_at,
                   cover.token as cover_token, cover.format as cover_format, c.cover_alt_en, c.cover_alt_fr,
                   b.allocated, b.delivered
              from aid_case c
              join case_balance b on b.case_id = c.id
              left join public_media cover on cover.id = c.cover_media_id
             where c.status <> 'draft'
            """;

    /** The directory lists cases only, never the fund. Each searched field is tested on its own. */
    private static final String DIRECTORY_FILTER = """
               and c.kind = 'case'
               and c.status in (:statuses)
               and (:category::text is null or c.category_id = :category)
               and (:q = '' or strpos(c.search_title_en, :q) > 0 or strpos(c.search_title_fr, :q) > 0
                            or strpos(c.search_public_name, :q) > 0 or strpos(c.search_code, :q) > 0)
            """;

    /**
     * An exact sort key for the funding ratio max(received, 0) / goal.
     *
     * It is floor(received * 10^25 / goal) in numeric arithmetic. Two different ratios with
     * goals up to 10^10 differ by at least 10^-20, so their keys differ by at least 10^5 and
     * keep their order; equal ratios give equal keys. No floating point, no 64-bit product.
     */
    private static final String RATIO = "div(greatest(b.allocated, 0) * 10000000000000000000000000::numeric, c.goal_cents::numeric)";

    private static final String TIE_BREAK = "c.published_at desc, c.code asc";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CaseRow> findPublicByCode(String canonicalCode) {
        return jdbc.sql(PUBLIC_CASES + " and c.code = :code").param("code", canonicalCode).query(CaseRepository::caseRow).optional();
    }

    /** What deciding a status needs. {@code received} is the case's allocated total, as the public response names it. */
    public record StatusRow(String code, String kind, String status, Long goalCents, long received) {
    }

    /**
     * Locks a case row for the rest of the transaction and reads what its status depends on.
     * The ledger takes the same lock when it writes an entry on the case, so within a
     * transaction that has just written one this waits for nothing.
     */
    public Optional<StatusRow> lockForStatus(long caseId) {
        return jdbc.sql("""
                        select c.code, c.kind, c.status, c.goal_cents,
                               (select b.allocated from case_balance b where b.case_id = c.id) as received
                          from aid_case c
                         where c.id = :id
                           for update of c
                        """)
                .param("id", caseId)
                .query((rs, row) -> new StatusRow(rs.getString("code"), rs.getString("kind"), rs.getString("status"),
                        rs.getObject("goal_cents", Long.class), rs.getBigDecimal("received").longValueExact()))
                .optional();
    }

    public void setStatus(long caseId, String status) {
        jdbc.sql("update aid_case set status = :status where id = :id").param("status", status).param("id", caseId).update();
    }

    public List<CaseRow> directory(CaseQuery query) {
        return jdbc.sql(PUBLIC_CASES + DIRECTORY_FILTER + " order by " + orderBy(query.sort()) + " limit :limit offset :offset")
                .param("statuses", query.statuses())
                .param("category", query.category())
                .param("q", query.normalizedSearch())
                .param("limit", query.page().pageSize())
                .param("offset", query.page().offset())
                .query(CaseRepository::caseRow)
                .list();
    }

    public long countDirectory(CaseQuery query) {
        return jdbc.sql("select count(*) from aid_case c where c.status <> 'draft'" + DIRECTORY_FILTER)
                .param("statuses", query.statuses())
                .param("category", query.category())
                .param("q", query.normalizedSearch())
                .query(Long.class)
                .single();
    }

    /** Chosen from fixed text by an enum; nothing from the request reaches the SQL string. */
    private static String orderBy(CaseQuery.Sort sort) {
        String primary = switch (sort) {
            case NEWEST -> "";
            case DEADLINE -> "c.deadline_ends_at asc nulls last, ";
            case CLOSEST -> """
                    case when c.goal_cents is null then 2 when b.allocated < c.goal_cents then 0 else 1 end,
                    case when c.goal_cents is null then 0 when b.allocated < c.goal_cents then -%s else %s end,
                    """.formatted(RATIO, RATIO);
            case LEAST -> "(c.goal_cents is null), " + RATIO + " asc nulls last, ";
            case FEATURED -> "c.featured_rank asc nulls last, ";
        };
        return primary + TIE_BREAK;
    }

    public boolean categoryExists(String id) {
        return jdbc.sql("select exists (select 1 from category where id = :id)").param("id", id).query(Boolean.class).single();
    }

    /** Active categories, plus inactive ones a public case still uses. */
    public List<CategoryResponse> listedCategories() {
        return jdbc.sql("""
                        select g.id, g.label_en, g.label_fr, g.position, g.active
                          from category g
                         where g.active
                            or exists (select 1 from aid_case c where c.category_id = g.id and c.status <> 'draft')
                         order by g.position, g.id
                        """)
                .query((rs, row) -> new CategoryResponse(
                        rs.getString("id"),
                        new CategoryResponse.Labels(rs.getString("label_en"), rs.getString("label_fr")),
                        rs.getInt("position"),
                        rs.getBoolean("active")))
                .list();
    }

    public List<CasePhotoResponse> photos(long caseId, Language language) {
        return jdbc.sql("""
                        select p.public_id, m.token, m.format, p.alt_en, p.alt_fr, p.position
                          from case_photo p
                          join public_media m on m.id = p.media_id
                         where p.case_id = :caseId
                         order by p.position
                        """)
                .param("caseId", caseId)
                .query((rs, row) -> {
                    Localized alt = Localized.pick(language, rs.getString("alt_en"), rs.getString("alt_fr"));
                    String token = rs.getString("token");
                    String format = rs.getString("format");
                    return new CasePhotoResponse(
                            rs.getString("public_id"),
                            MediaPath.of(token, format, MediaPath.DISPLAY),
                            MediaPath.of(token, format, MediaPath.THUMBNAIL),
                            alt.text(),
                            alt.language(),
                            rs.getInt("position"));
                })
                .list();
    }

    /** The author's picture is selected only when the author agreed to show it. */
    public List<CaseUpdateResponse> updates(long caseId, String caseCode, Language language, PageRequest page) {
        return jdbc.sql("""
                        select u.public_id, u.text_en, u.text_fr, u.posted_at, u.author_name,
                               photo.token as photo_token, photo.format as photo_format, u.photo_alt_en, u.photo_alt_fr,
                               case when u.author_avatar_consent then avatar.token end as avatar_token,
                               avatar.format as avatar_format
                          from case_update u
                          left join public_media photo on photo.id = u.photo_media_id
                          left join public_media avatar on avatar.id = u.author_avatar_media_id
                         where u.case_id = :caseId
                         order by u.posted_at desc, u.public_id desc
                         limit :limit offset :offset
                        """)
                .param("caseId", caseId)
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query((rs, row) -> {
                    Localized text = Localized.pick(language, rs.getString("text_en"), rs.getString("text_fr"));
                    String photoToken = rs.getString("photo_token");
                    Localized photoAlt = photoToken == null
                            ? Localized.NONE
                            : Localized.pick(language, rs.getString("photo_alt_en"), rs.getString("photo_alt_fr"));
                    return new CaseUpdateResponse(
                            rs.getString("public_id"),
                            caseCode,
                            text.text(),
                            text.language(),
                            MediaPath.of(photoToken, rs.getString("photo_format"), MediaPath.DISPLAY),
                            photoAlt.text(),
                            photoAlt.language(),
                            Timestamps.format(instant(rs, "posted_at")),
                            rs.getString("author_name"),
                            MediaPath.of(rs.getString("avatar_token"), rs.getString("avatar_format"), MediaPath.THUMBNAIL));
                })
                .list();
    }

    public long countUpdates(long caseId) {
        return jdbc.sql("select count(*) from case_update where case_id = :caseId").param("caseId", caseId).query(Long.class).single();
    }

    private static CaseRow caseRow(ResultSet rs, int row) throws SQLException {
        return new CaseRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getBoolean("accepts_donations"),
                rs.getString("category_id"),
                rs.getObject("featured_rank", Integer.class),
                rs.getString("public_name"),
                rs.getString("title_en"),
                rs.getString("title_fr"),
                rs.getString("story_en"),
                rs.getString("story_fr"),
                rs.getObject("goal_cents", Long.class),
                rs.getObject("deadline_date", LocalDate.class),
                rs.getString("deadline_timezone"),
                instant(rs, "deadline_ends_at"),
                instant(rs, "published_at"),
                rs.getString("cover_token"),
                rs.getString("cover_format"),
                rs.getString("cover_alt_en"),
                rs.getString("cover_alt_fr"),
                rs.getBigDecimal("allocated").longValueExact(),
                rs.getBigDecimal("delivered").longValueExact());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
