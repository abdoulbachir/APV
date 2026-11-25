package org.apvgakasinay.ledger.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.apvgakasinay.ledger.CaseFinancials;
import org.apvgakasinay.ledger.LedgerEntryResponse;
import org.apvgakasinay.ledger.PlatformTotals;
import org.apvgakasinay.ledger.PublicDonorResponse;
import org.apvgakasinay.media.MediaPath;
import org.apvgakasinay.shared.Language;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL of the ledger's read side. Totals come back as numeric and are exact. */
@Repository
public class LedgerRepository {

    /** A donor is listed when not hidden and something of the donation still stands. */
    private static final String LISTED_DONORS = """
              from donation_listing l
              join ledger_entry_detail d on d.id = l.ledger_entry_id
              left join public_media avatar on avatar.id = l.avatar_media_id
             where d.case_id = :caseId
               and l.visibility <> 'hidden'
               and d.amount_cents - d.corrected_cents > 0
            """;

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CaseFinancials financials(long caseId) {
        return jdbc.sql("select external_donations, transfers_in, transfers_out, delivered from case_balance where case_id = :caseId")
                .param("caseId", caseId)
                .query((rs, row) -> CaseFinancials.of(
                        exact(rs, "external_donations"), exact(rs, "transfers_in"), exact(rs, "transfers_out"), exact(rs, "delivered")))
                .single();
    }

    public PlatformTotals platformTotals() {
        return jdbc.sql("""
                        select coalesce(sum(b.external_donations), 0) as received,
                               coalesce(sum(b.delivered), 0) as delivered,
                               count(*) filter (where c.kind = 'case' and b.delivered > 0) as cases_helped
                          from case_balance b
                          join aid_case c on c.id = b.case_id
                        """)
                .query((rs, row) -> new PlatformTotals(exact(rs, "received"), exact(rs, "delivered"), rs.getLong("cases_helped")))
                .single();
    }

    public List<LedgerEntryResponse> entries(long caseId, Language language, PageRequest page) {
        return jdbc.sql("""
                        select public_id, kind, amount_cents, allocated_delta, delivered_delta, effective_at,
                               label_en, label_fr, corrects_public_id, corrected_by, corrected_cents, counterpart_code
                          from ledger_entry_detail
                         where case_id = :caseId
                         order by effective_at desc, public_id desc
                         limit :limit offset :offset
                        """)
                .param("caseId", caseId)
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query((rs, row) -> {
                    long amount = rs.getLong("amount_cents");
                    long corrected = exact(rs, "corrected_cents");
                    return new LedgerEntryResponse(
                            rs.getString("public_id"),
                            rs.getString("kind"),
                            amount,
                            rs.getLong("allocated_delta"),
                            rs.getLong("delivered_delta"),
                            timestamp(rs, "effective_at"),
                            rs.getString(language == Language.EN ? "label_en" : "label_fr"),
                            rs.getString("corrects_public_id"),
                            strings(rs.getArray("corrected_by")),
                            corrected,
                            amount - corrected,
                            rs.getString("counterpart_code"));
                })
                .list();
    }

    public long countEntries(long caseId) {
        return jdbc.sql("select count(*) from ledger_entry_detail where case_id = :caseId")
                .param("caseId", caseId).query(Long.class).single();
    }

    /**
     * The name is selected only for a named donation, and the picture only for a named donation
     * whose donor agreed to show it: private values never leave the database.
     */
    public List<PublicDonorResponse> donors(long caseId, PageRequest page) {
        return jdbc.sql("""
                        select l.public_id,
                               case when l.visibility = 'named' then l.donor_name end as shown_name,
                               d.amount_cents - d.corrected_cents as net_cents,
                               d.effective_at,
                               case when l.visibility = 'named' and l.avatar_consent then avatar.token end as avatar_token,
                               avatar.format as avatar_format
                        """ + LISTED_DONORS + """
                         order by d.effective_at desc, l.public_id desc
                         limit :limit offset :offset
                        """)
                .param("caseId", caseId)
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query((rs, row) -> new PublicDonorResponse(
                        rs.getString("public_id"),
                        rs.getString("shown_name"),
                        exact(rs, "net_cents"),
                        timestamp(rs, "effective_at"),
                        MediaPath.of(rs.getString("avatar_token"), rs.getString("avatar_format"), MediaPath.THUMBNAIL)))
                .list();
    }

    public long countDonors(long caseId) {
        return jdbc.sql("select count(*) " + LISTED_DONORS).param("caseId", caseId).query(Long.class).single();
    }

    private static long exact(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).longValueExact();
    }

    private static String timestamp(ResultSet rs, String column) throws SQLException {
        return Timestamps.format(rs.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
