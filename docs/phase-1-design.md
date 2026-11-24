# Humanity in Motion — Phase 1

Visual sample only: home and case detail. Keep mock mode; no payment, auth, backend changes or new Phase 2 routes. Artwork is original symbolic SVG, not a real beneficiary photograph. Use real approved public photos only when provided. The existing public sample deployment is not updated by this branch.

## Verification for Traycer

Run npm ci and npm run build. Start npm run dev, then inspect / and /cases/HELP-014 at widths 360, 390, 768, 1024 and 1440, both EN and FR. Check scroll links, keyboard focus, mock loading/error, giving sample notice, funding numbers, donor and ledger lists, reduced motion and no horizontal overflow. Do not merge until the owner approves. The artwork is a first-pass vector interpretation of the visual reference; photography-quality assets and browser verification remain open.

## PR review fixes

Consolidated the stylesheet and tokens, removed obsolete visual rules and SVG turbulence filters, bounded excerpts, restored the sample fund note, moved optional fixture artwork out of the reusable portrait component, separated and accurately scaled the received/delivered progress tracks, localized case loading/error titles, and keyed anchor scrolling to navigation rather than locale reloads. Re-run the complete visual checks; in particular check EN/FR at 768 and 1024, repeated #cases clicks, locale changes, deep links, 404 titles, long case stories, photo failures, 0/null/over-goal funding, and contrast. The previous build was reported passing by Traycer; this follow-up requires a fresh build and browser review.
