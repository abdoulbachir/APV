package org.apvgakasinay.donations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * All SQL of payment methods (contract/donation-lifecycle.md, section 2). A method is a record;
 * its recipient details are versions that are never edited: publishing supersedes the current
 * version and inserts the next, in one transaction.
 */
@Repository
public class PaymentMethodRepository {

    /** Both languages of one text. */
    public record Localized(String en, String fr) {
    }

    /** One recipient detail shown to the donor. {@code format} is {@code text} or {@code link}. */
    public record RecipientField(Localized labels, String value, String format) {
    }

    public record Method(String id, String nameEn, String nameFr, int position, boolean active, Instant deactivatedAt,
                         String eligibilityCheckedBy, LocalDate eligibilityCheckedOn, String eligibilityNotes) {
    }

    public record Details(long id, String publicId, String methodId, int revision, Localized instructions,
                          List<RecipientField> recipient, Instant publishedAt, Instant supersededAt) {
    }

    /** A details version with what its method is doing now. */
    public record ShownDetails(Details details, Method method) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String METHOD = """
            select m.id, m.name_en, m.name_fr, m.position, m.active, m.deactivated_at,
                   m.eligibility_checked_by, m.eligibility_checked_on, m.eligibility_notes
              from payment_method m
            """;

    private static final String DETAILS = """
            select d.id as details_id, d.public_id, d.method_id, d.revision, d.instructions_en, d.instructions_fr,
                   d.recipient::text as recipient, d.published_at, d.superseded_at
              from payment_method_details d
            """;

    private final JdbcClient jdbc;

    public PaymentMethodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Method method(ResultSet rs, int row) throws SQLException {
        Timestamp deactivated = rs.getTimestamp("deactivated_at");
        return new Method(rs.getString("id"), rs.getString("name_en"), rs.getString("name_fr"), rs.getInt("position"),
                rs.getBoolean("active"), deactivated == null ? null : deactivated.toInstant(),
                rs.getString("eligibility_checked_by"), rs.getObject("eligibility_checked_on", LocalDate.class),
                rs.getString("eligibility_notes"));
    }

    private static Details details(ResultSet rs, int row) throws SQLException {
        Timestamp superseded = rs.getTimestamp("superseded_at");
        return new Details(rs.getLong("details_id"), rs.getString("public_id"), rs.getString("method_id"), rs.getInt("revision"),
                new Localized(rs.getString("instructions_en"), rs.getString("instructions_fr")),
                recipient(rs.getString("recipient")), rs.getTimestamp("published_at").toInstant(),
                superseded == null ? null : superseded.toInstant());
    }

    @SuppressWarnings("unchecked")
    static List<RecipientField> recipient(String json) {
        List<RecipientField> fields = new ArrayList<>();
        for (Object item : JSON.readValue(json, List.class)) {
            Map<String, Object> field = (Map<String, Object>) item;
            Map<String, Object> labels = (Map<String, Object>) field.get("labels");
            fields.add(new RecipientField(new Localized((String) labels.get("en"), (String) labels.get("fr")),
                    (String) field.get("value"), (String) field.get("format")));
        }
        return fields;
    }

    static String json(List<RecipientField> fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecipientField field : fields) {
            Map<String, Object> labels = new LinkedHashMap<>();
            labels.put("en", field.labels().en());
            labels.put("fr", field.labels().fr());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("labels", labels);
            item.put("value", field.value());
            item.put("format", field.format());
            out.add(item);
        }
        return JSON.writeValueAsString(out);
    }

    // ------------------------------------------------------------------ reads

    /** Active methods in display order, each with its current details version. A method without one is not listed. */
    public List<ShownDetails> activeWithCurrentDetails() {
        return jdbc.sql("""
                        select m.id, m.name_en, m.name_fr, m.position, m.active, m.deactivated_at,
                               m.eligibility_checked_by, m.eligibility_checked_on, m.eligibility_notes,
                               d.id as details_id, d.public_id, d.method_id, d.revision, d.instructions_en, d.instructions_fr,
                               d.recipient::text as recipient, d.published_at, d.superseded_at
                          from payment_method m
                          join payment_method_details d on d.method_id = m.id and d.superseded_at is null
                         where m.active
                         order by m.position, m.id
                        """)
                .query((rs, row) -> new ShownDetails(details(rs, row), method(rs, row))).list();
    }

    public List<Method> methods() {
        return jdbc.sql(METHOD + " order by m.position, m.id").query(PaymentMethodRepository::method).list();
    }

    public Optional<Method> method(String id) {
        return jdbc.sql(METHOD + " where m.id = :id").param("id", id).query(PaymentMethodRepository::method).optional();
    }

    /** Reads a method and locks its row until the transaction ends: one change to a method at a time. */
    public Optional<Method> lockMethod(String id) {
        return jdbc.sql(METHOD + " where m.id = :id for update").param("id", id).query(PaymentMethodRepository::method).optional();
    }

    public Optional<Details> currentDetails(String methodId) {
        return jdbc.sql(DETAILS + " where d.method_id = :method and d.superseded_at is null").param("method", methodId)
                .query(PaymentMethodRepository::details).optional();
    }

    public List<Details> allDetails(String methodId) {
        return jdbc.sql(DETAILS + " where d.method_id = :method order by d.revision").param("method", methodId)
                .query(PaymentMethodRepository::details).list();
    }

    public Optional<Details> detailsByPublicId(String publicId) {
        return jdbc.sql(DETAILS + " where d.public_id = :id").param("id", publicId).query(PaymentMethodRepository::details).optional();
    }

    public Optional<Details> detailsById(long id) {
        return jdbc.sql(DETAILS + " where d.id = :id").param("id", id).query(PaymentMethodRepository::details).optional();
    }

    // ------------------------------------------------------------------ writes

    public boolean insertMethod(String id, String nameEn, String nameFr, int position, Instant now) {
        return jdbc.sql("""
                        insert into payment_method (id, name_en, name_fr, position, active, created_at)
                        values (:id, :en, :fr, :position, false, :now)
                        on conflict (id) do nothing
                        """)
                .param("id", id).param("en", nameEn).param("fr", nameFr).param("position", position)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    public boolean positionTaken(int position) {
        return jdbc.sql("select exists (select 1 from payment_method where position = :position)").param("position", position)
                .query(Boolean.class).single();
    }

    public void supersede(long detailsId, Instant at) {
        jdbc.sql("update payment_method_details set superseded_at = :at where id = :id and superseded_at is null")
                .param("at", Timestamp.from(at)).param("id", detailsId).update();
    }

    public void insertDetails(String publicId, String methodId, int revision, Localized instructions, List<RecipientField> recipient, Instant at) {
        jdbc.sql("""
                        insert into payment_method_details (public_id, method_id, revision, instructions_en, instructions_fr, recipient, published_at)
                        values (:publicId, :method, :revision, :en, :fr, :recipient::jsonb, :at)
                        """)
                .param("publicId", publicId).param("method", methodId).param("revision", revision)
                .param("en", instructions.en()).param("fr", instructions.fr()).param("recipient", json(recipient))
                .param("at", Timestamp.from(at)).update();
    }

    public void activate(String id, String checkedBy, LocalDate checkedOn, String notes) {
        jdbc.sql("""
                        update payment_method
                           set active = true, deactivated_at = null,
                               eligibility_checked_by = coalesce(:by, eligibility_checked_by),
                               eligibility_checked_on = coalesce(:on, eligibility_checked_on),
                               eligibility_notes = coalesce(:notes, eligibility_notes)
                         where id = :id
                        """)
                .param("by", checkedBy).param("on", checkedOn).param("notes", notes).param("id", id).update();
    }

    public void deactivate(String id, Instant at) {
        jdbc.sql("update payment_method set active = false, deactivated_at = :at where id = :id")
                .param("at", Timestamp.from(at)).param("id", id).update();
    }
}
