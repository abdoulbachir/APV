# Case directory — design extension

Stacked on design/homepage-v2. Homepage shows one featured case and up to three compact cards. /cases offers 12 results per page, search, category (mock only), sorting, grid/list, shareable query parameters and a return link preserving the directory URL. Both themes and languages use shared components. 199 deterministic fictional records plus HELP-014 = 200 active cases; FUND is excluded. The mock totals include the additional fictional gifts and disbursements. Existing original sample record is preserved. All fictional cases have working detail routes.

## API boundary

The existing typed ApiClient and real endpoints are unchanged. Current getCases returns an array; filtering and pagination happen in the frontend for this prototype. Categories are mock-only metadata inferred from the generated fixture code; real mode hides the category control and does not infer categories. Real featured selection temporarily uses newest first. Discuss with backend owner before production: add explicit category, optional featured rank, server-side query/status/sort/page/limit and total metadata, stable ordering and validation; preserve integer cents and public-only data. No backend changes in this PR.

## Review checklist

- Production build; first/last page (12 / 8 results), 200 records, unique codes, no fund card, no drafts.
- Search code/name/title, accent-insensitive French, category, sort including null deadlines/goal, invalid page parameters, empty state, URL reload and back/forward.
- All mock case detail routes; return to filtered page, original case detail, exact cents, delivered vs received; clear sample disclosure.
- Both themes, languages, 360/768/1024/1440/2544px, grid/list, keyboard, reduced motion, no overflow, lazy photos and neutral artwork fallback.
- Photo binaries still need to be committed on base branch and integrated before visual sign-off.
