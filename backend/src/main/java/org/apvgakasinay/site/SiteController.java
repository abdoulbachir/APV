package org.apvgakasinay.site;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Site settings and platform totals. GET only. Neither is localized. */
@RestController
@RequestMapping("/api")
class SiteController {

    private final SiteService site;

    SiteController(SiteService site) {
        this.site = site;
    }

    @GetMapping("/site")
    SiteResponse site() {
        return site.site();
    }

    @GetMapping("/stats")
    StatsResponse stats() {
        return site.stats();
    }
}
