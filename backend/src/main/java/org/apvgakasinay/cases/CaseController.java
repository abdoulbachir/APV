package org.apvgakasinay.cases;

import java.util.Map;
import org.apvgakasinay.ledger.LedgerEntryResponse;
import org.apvgakasinay.ledger.PublicDonorResponse;
import org.apvgakasinay.shared.Language;
import org.apvgakasinay.shared.Page;
import org.apvgakasinay.shared.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The five case endpoints of the public read contract. GET only. */
@RestController
@RequestMapping("/api/cases")
class CaseController {

    private final CaseReadService cases;

    CaseController(CaseReadService cases) {
        this.cases = cases;
    }

    @GetMapping
    ResponseEntity<Page<CaseResponse>> list(
            @RequestParam Map<String, String> query,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Language language = Language.negotiate(acceptLanguage);
        return localized(language, cases.directory(CaseQuery.from(query), language));
    }

    @GetMapping("/{code}")
    ResponseEntity<CaseDetailResponse> detail(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Language language = Language.negotiate(acceptLanguage);
        return localized(language, cases.detail(code, language));
    }

    @GetMapping("/{code}/updates")
    ResponseEntity<Page<CaseUpdateResponse>> updates(
            @PathVariable String code,
            @RequestParam Map<String, String> query,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Language language = Language.negotiate(acceptLanguage);
        return localized(language, cases.updates(code, language, PageRequest.from(query)));
    }

    /** Not localized: a donor listing has no translated text. */
    @GetMapping("/{code}/donors")
    Page<PublicDonorResponse> donors(@PathVariable String code, @RequestParam Map<String, String> query) {
        return cases.donors(code, PageRequest.from(query));
    }

    @GetMapping("/{code}/ledger")
    ResponseEntity<Page<LedgerEntryResponse>> ledger(
            @PathVariable String code,
            @RequestParam Map<String, String> query,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Language language = Language.negotiate(acceptLanguage);
        return localized(language, cases.ledger(code, language, PageRequest.from(query)));
    }

    private static <T> ResponseEntity<T> localized(Language language, T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_LANGUAGE, language.tag())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
                .body(body);
    }
}
