package org.apvgakasinay.cases;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apvgakasinay.cases.internal.CaseRepository;
import org.apvgakasinay.cases.internal.CaseRow;
import org.apvgakasinay.shared.AppClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a case code to a {@link ResolvedCase} for the donations module, which owns gifts but
 * reads cases only through this module. A draft and an unknown code are indistinguishable: both
 * resolve to empty, as everywhere in the public read model. The lookup is case-insensitive.
 *
 * <p>Called from the donations module's own transaction (reporting, confirmation): it joins that
 * transaction and reads the committed case state under it, so a case decision and the gift it
 * affects see one consistent snapshot.
 */
@Service
@Transactional(readOnly = true)
public class CaseResolver {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z]{2,8}(-[0-9]{1,6})?$");

    private final CaseRepository repository;
    private final AppClock clock;

    public CaseResolver(CaseRepository repository, AppClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<ResolvedCase> resolve(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        Instant now = clock.now();
        return repository.findPublicByCode(code.toUpperCase(Locale.ROOT)).map(row -> toResolved(row, now));
    }

    private static ResolvedCase toResolved(CaseRow row, Instant now) {
        boolean open = row.status().equals("active") || row.status().equals("funded");
        boolean beforeCutoff = row.deadlineEndsAt() == null || now.isBefore(row.deadlineEndsAt());
        boolean accepting = row.acceptsDonations() && open && beforeCutoff;
        return new ResolvedCase(row.id(), row.code(), row.kind(), row.status(), accepting);
    }
}
