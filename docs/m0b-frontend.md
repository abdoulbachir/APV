# M0B frontend public read integration

Use Node 24 (`.nvmrc`). Run `npm ci`, `npm test`, `npm run build`. Mock preview remains the default for design review; it loads only src/api/dataset.public.json (a byte-identical copy of the validated contract public projection) and labels fictional data. The real API never falls back to the mock.

With the backend and development fixtures running on port 8080, use `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev`. Vite forwards /api and /media/public to localhost:8080. For a real-mode build use `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run build` for fixture integration. Production must explicitly use VITE_API_MODE=real without VITE_SAMPLE_DATA, behind the same-origin reverse proxy; this is not production release authorization.

Run `cd contract && npm ci && npm run validate` for schema checks and independent mock/reference response parity. The mock recomputes totals, corrections, page metadata and search/sort; it does not use expected response files. Media tokens are masked in parity tests because the backend generates opaque tokens independently. Public DTOs, query types and endpoint paths are generated from OpenAPI. Run `cd contract && npm run generate:types` after an approved contract change. Contract CI checks the generated file for drift. The generator deliberately fails on unsupported schema constructs; extend it with tests when new contract features require them. TypeScript types do not replace runtime schema or financial validation.

No actual giving functionality, accounts or payment services are enabled. Real integration, browser accessibility and visual regression testing remain acceptance gates. History uses 10-entry previews and loads pages of 10; when total changes or a duplicate ID appears the user must reload. Like the contract's offset pagination, it is not a stable snapshot across multiple requests.

Backend coordination: the shared fixture commit c2f70c628e97852876e6f53e495e40ad0851ec8e (including its parent fixture commit) can be merged or cherry-picked by agreement without taking frontend application changes. Use the full private fixture only for development/test backend seed data. Serve fixture pictures from contract/fixtures/images; never expose arbitrary fixture files.

## Fixture privacy in development

Vite denies the entire contract, backend and docs trees in addition to its standard sensitive-file rules. The browser imports only the sanitized copy at `src/api/dataset.public.json`. After intentionally regenerating the shared dataset, copy `contract/fixtures/dataset.public.json` to that frontend path; `npm test` fails on drift and checks private paths over a real Vite HTTP server. Do not copy the full fixture or expected/private marker files into `src/` or `public/`.

History controls remain focusable with `aria-disabled` during loading and on the last page and announce the newly loaded and total entries. Case headings receive focus on new SPA arrivals, not direct loads, history traversal or locale-only reloads.
