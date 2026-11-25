package org.apvgakasinay.cases;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apvgakasinay.cases.internal.CaseRepository;
import org.apvgakasinay.cases.internal.CaseRow;
import org.apvgakasinay.ledger.LedgerEntryResponse;
import org.apvgakasinay.ledger.LedgerReadService;
import org.apvgakasinay.ledger.PublicDonorResponse;
import org.apvgakasinay.media.MediaPath;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.Language;
import org.apvgakasinay.shared.Localized;
import org.apvgakasinay.shared.Page;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public reads of cases. Each method is one read-only transaction at repeatable-read
 * isolation, so every number in a response comes from the same database snapshot.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CaseReadService {

    private static final int PREVIEW_SIZE = 10;
    private static final PageRequest PREVIEW = new PageRequest(1, PREVIEW_SIZE);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z]{2,8}(-[0-9]{1,6})?$");

    private final CaseRepository repository;
    private final LedgerReadService ledger;
    private final AppClock clock;

    public CaseReadService(CaseRepository repository, LedgerReadService ledger, AppClock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.clock = clock;
    }

    public Page<CaseResponse> directory(CaseQuery query, Language language) {
        if (query.category() != null && !repository.categoryExists(query.category())) {
            throw ApiException.invalid("category", "invalid_value", "unknown category");
        }
        Instant now = clock.now();
        List<CaseResponse> items = repository.directory(query).stream().map(row -> toResponse(row, language, now)).toList();
        return Page.of(items, query.page(), repository.countDirectory(query));
    }

    public CaseDetailResponse detail(String code, Language language) {
        Instant now = clock.now();
        CaseRow row = find(code);
        List<CasePhotoResponse> photos = repository.photos(row.id(), language);
        Page<LedgerEntryResponse> entries = ledger.entries(row.id(), language, PREVIEW);
        Page<PublicDonorResponse> donors = ledger.donors(row.id(), PREVIEW);
        return new CaseDetailResponse(
                toResponse(row, language, now),
                ledger.financials(row.id()),
                photos,
                repository.updates(row.id(), row.code(), language, PREVIEW),
                donors.items(),
                entries.items(),
                new CaseDetailResponse.Counts(repository.countUpdates(row.id()), donors.totalItems(), entries.totalItems(), photos.size()),
                Timestamps.format(now));
    }

    public Page<CaseUpdateResponse> updates(String code, Language language, PageRequest page) {
        CaseRow row = find(code);
        return Page.of(repository.updates(row.id(), row.code(), language, page), page, repository.countUpdates(row.id()));
    }

    public Page<PublicDonorResponse> donors(String code, PageRequest page) {
        return ledger.donors(find(code).id(), page);
    }

    public Page<LedgerEntryResponse> ledger(String code, Language language, PageRequest page) {
        return ledger.entries(find(code).id(), language, page);
    }

    /** Case-insensitive lookup. A draft and an unknown code are indistinguishable: both 404. */
    private CaseRow find(String code) {
        if (!CODE.matcher(code).matches()) {
            throw ApiException.caseNotFound();
        }
        return repository.findPublicByCode(code.toUpperCase(Locale.ROOT)).orElseThrow(ApiException::caseNotFound);
    }

    /** The allowlist: a public response is built field by field, never from a database row as a whole. */
    private static CaseResponse toResponse(CaseRow row, Language language, Instant now) {
        Localized title = Localized.pick(language, row.titleEn(), row.titleFr());
        Localized story = Localized.pick(language, row.storyEn(), row.storyFr());
        Localized photoAlt = row.coverToken() == null ? Localized.NONE : Localized.pick(language, row.coverAltEn(), row.coverAltFr());
        boolean open = row.status().equals("active") || row.status().equals("funded");
        boolean beforeCutoff = row.deadlineEndsAt() == null || now.isBefore(row.deadlineEndsAt());
        return new CaseResponse(
                row.code(),
                row.kind(),
                row.status(),
                row.acceptsDonations() && open && beforeCutoff,
                row.categoryId(),
                row.featuredRank(),
                title.text(),
                title.language(),
                row.publicName(),
                story.text(),
                story.language(),
                MediaPath.of(row.coverToken(), row.coverFormat(), MediaPath.DISPLAY),
                MediaPath.of(row.coverToken(), row.coverFormat(), MediaPath.THUMBNAIL),
                photoAlt.text(),
                photoAlt.language(),
                row.goalCents(),
                row.allocated(),
                row.delivered(),
                row.deadlineDate() == null ? null : row.deadlineDate().toString(),
                row.deadlineTimezone(),
                Timestamps.format(row.deadlineEndsAt()),
                Timestamps.format(row.publishedAt()));
    }
}
