# M0B frontend public read integration

Use Node 24 (`.nvmrc`). Run `npm ci`, `npm test`, `npm run build`. Mock preview remains the default for design review; it loads only contract/fixtures/dataset.public.json and labels fictional data. The real API never falls back to the mock.

With the backend and development fixtures running on port 8080, use `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev`. Vite forwards /api and /media/public to localhost:8080. For a real-mode build use `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run build` for fixture integration. Production must explicitly use VITE_API_MODE=real without VITE_SAMPLE_DATA, behind the same-origin reverse proxy; this is not production release authorization.

Run `cd contract && npm ci && npm run validate` for schema checks and independent mock/reference response parity. The mock recomputes totals, corrections, page metadata and search/sort; it does not use expected response files. Media tokens are masked in parity tests because the backend generates opaque tokens independently. Public DTO types currently mirror OpenAPI by hand; automated generation and drift validation remain a follow-up.

No actual giving functionality, accounts or payment services are enabled. Real integration, browser accessibility and visual regression testing remain acceptance gates. History uses 10-entry previews and loads pages of 10; when total changes or a duplicate ID appears the user must reload. Like the contract's offset pagination, it is not a stable snapshot across multiple requests.

Backend coordination: the shared fixture commit c2f70c628e97852876e6f53e495e40ad0851ec8e (including its parent fixture commit) can be merged or cherry-picked by agreement without taking frontend application changes. Use the full private fixture only for development/test backend seed data. Serve fixture pictures from contract/fixtures/images; never expose arbitrary fixture files.
