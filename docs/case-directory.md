# Case directory — design extension

Stacked on design/homepage-v2. Homepage shows one featured case and up to three compact cards. /cases offers 12 results per page, search, category (mock only), sorting, grid/list, shareable query parameters and a return link preserving the directory URL. Both themes and languages use shared components. 199 deterministic fictional records plus HELP-014 = 200 active cases; FUND is excluded. The mock totals include the additional fictional gifts and disbursements. Existing original sample record is preserved. All fictional cases have working detail routes.

## API boundary

The existing typed ApiClient and real endpoints are unchanged. Current getCases returns an array; filtering and pagination happen in the frontend for this prototype. Categories are mock-only metadata inferred from the generated fixture code; real mode hides the category control and does not infer categories. Real featured selection temporarily uses newest first. Discuss with backend owner before production: add explicit category, optional featured rank, server-side query/status/sort/page/limit and total metadata, stable ordering and validation; preserve integer cents and public-only data. No backend changes in this PR.

## Review checklist

- Production build; first/last page (12 / 8 results), 200 records, unique codes, no fund card, no drafts.
- Search code/name/title, accent-insensitive French, category, sort including null deadlines/goal, invalid page parameters, empty state, URL reload and back/forward.
- All mock case detail routes; return to filtered page, original case detail, exact cents, delivered vs received; clear sample disclosure.
- Both themes, languages, 360/768/1024/1440/2544px, grid/list, keyboard, reduced motion, no overflow, lazy photos and neutral artwork fallback.
- Photo binaries are committed on the stacked base and were verified in the production build.

## Review follow-up

Use Node 24 (the .nvmrc target) for development and CI; package engines require Node >=22. Run `nvm use`, `npm ci`, both audits, and `npm run build`. Dev and preview default to loopback; use `npm run dev:network` or `npm run preview:network` only for intentional LAN testing. Do not expose Vite publicly.

In development, append `?mockError=1` to a mock page to reproduce a deterministic 503. The query is ignored in production builds and real API mode. Remove the query (or use Retry, which clears this dev-only scenario) to exercise recovery. This replaces random failure injection.

Regression: unknown URL and missing case show localized 404 content; transient failures retain Retry; skip link focuses main; direct directory load does not focus the H1; invalid recognized filter values canonicalize without dropping unrelated parameters. HELP-014 includes additional explicitly fictional ledger entries to reconcile 567500 received and 320000 delivered cents, including a non-listed donor amount. Do not treat these invented sample entries as real financial records.
