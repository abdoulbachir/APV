package org.apvgakasinay.site;

import java.util.List;
import org.apvgakasinay.cases.CategoryService;
import org.apvgakasinay.ledger.LedgerReadService;
import org.apvgakasinay.ledger.PlatformTotals;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.Timestamps;
import org.apvgakasinay.site.internal.SettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SiteService {

    private final SettingsRepository settings;
    private final CategoryService categories;
    private final LedgerReadService ledger;
    private final AppClock clock;

    public SiteService(SettingsRepository settings, CategoryService categories, LedgerReadService ledger, AppClock clock) {
        this.settings = settings;
        this.categories = categories;
        this.ledger = ledger;
        this.clock = clock;
    }

    public SiteResponse site() {
        SettingsRepository.Settings current = settings.current();
        return new SiteResponse(
                current.timezone(),
                "USD",
                List.of("en", "fr"),
                current.defaultLocale(),
                categories.listed(),
                "FUND",
                new SiteResponse.Pagination(PageRequest.DEFAULT_PAGE_SIZE, PageRequest.MAX_PAGE_SIZE));
    }

    public StatsResponse stats() {
        String asOf = Timestamps.format(clock.now());
        PlatformTotals totals = ledger.platformTotals();
        return new StatsResponse(totals.received(), totals.delivered(), totals.received() - totals.delivered(), totals.casesHelped(), asOf);
    }
}
