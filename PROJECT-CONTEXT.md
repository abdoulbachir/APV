# APV-GAKASINAY — Project context and handoff

**Checkpoint:** 2026-11-25, M0B accepted and merged; M1A contract work initiated.
**Purpose:** Start a fresh conversation or onboard an agent without replaying the development history.
**Repository:** https://github.com/abdoulbachir/APV

This is a living orientation document, not a replacement for the API contract, approved decisions or implementation. The commit references below are a dated checkpoint, not a claim about the current remote heads. Always fetch and verify the actual branch/PR status before acting. Update this document after accepted milestones; distinguish approved decisions, implementation evidence, proposals and unresolved questions. Never put credentials or real beneficiary/donor information here.

## 1. Product and scope

APV-GAKASINAY is a small, trusted friends/family aid initiative. The public website presents individual assistance cases, the general fund, progress, donor listings and a transparent financial record. Visitors often arrive from group-chat links on phones. It is bilingual English/French, mobile-first and usable without technical knowledge.

The project records money transferred **outside** the website. It does not process payments or collect bank credentials, card numbers, OTPs or payment passwords. An account is optional for giving or reporting a gift. The initiative is not a registered charity; gifts are not tax-deductible. Do not claim charity status or provider eligibility. Real funds, donor information and beneficiary evidence require privacy controls.

Future payment methods: Zelle, PayPal, Cash App, Apple Cash (person-to-person, not Apple Pay), plus Other leading to a contact form. Methods are administrator-configurable records, with versioned recipient instructions/details, active status, ordering, bilingual instructions and audit history. No redeployment to add another compatible method. Verify provider terms/account/geographic eligibility before production. Never automatically confirm a gift from a screenshot. Donor reports are pending until the super administrator verifies actual receipt; reported and confirmed amounts are separate. Optional screenshots are private supporting evidence. Guest donation tracking and retention remain open.

## 2. Authoritative sources

- [Approved decisions](docs/decisions.md): overrides historical "pending" language for decisions actually approved.
- [Public API v0.2.0](contract/openapi.yaml): seven accepted public GET endpoints; preserve compatibility.
- [Financial terms](contract/financial-terms.md): ledger, exact money arithmetic, reconciliation, deadlines, lifecycle; read alongside approved decisions.
- [Privacy and media](contract/media-and-privacy.md): consent, closed public DTOs, public URLs, retention limits and caching.
- [Backend architecture ADR](docs/adr/0001-backend-architecture.md): approved stack, boundaries, deployment and session direction.
- [Approved design](docs/phase-1-design.md), [directory](docs/case-directory.md): visual and interaction baseline.
- [Frontend integration](docs/m0b-frontend.md), [joint integration](docs/m0b-integration.md), [backend startup](backend/README.md).
- [Contract checks](contract/README.md), [fixture documentation](contract/fixtures/README.md).
- [Historical M0A review](contract/M0A-review.md): review history, not current approval status. `API-CHANGES.md` is an old proposal, not the current contract.
- PRs, GitHub Actions and source code provide implementation and test evidence. Prefer exact commit-specific evidence over claims in agent summaries.

## 3. Approved design and application behavior

"Humanity in Motion": editorial, warm, human and modern, not a generic charity template. Light-first ivory/white/sage surfaces, forest green typography, emerald actions; dark cinematic alternative. Syne headings, Manrope body and Georgia editorial accents. Use existing design tokens and components; do not redesign accepted pages without approval. Finite motion and reduced-motion support. Symbolic AI-generated artwork (not actual beneficiary photographs) is disclosed; real public photos require approval/consent. The header brand mark is also the favicon.

Accepted routes: `/`, `/cases`, `/cases/:code` (including `FUND`), localized missing/error routes. Preserve directory URL state, filters, grid/list, page restoration, accessible navigation, case heading focus and history loading focus. EN/FR language choice and light/dark preference persist locally; do not store credentials or private data in localStorage.

Design acceptance: responsive down to 360px; 44px targets, WCAG AA contrast, keyboard/skip links, focus, screen-reader language metadata, no overflow or unexpected errors. Continue to test both languages and themes. No analytics or tracking SDK, no payment SDK, noindex until production decision. Current giving actions only explain that giving is unavailable.

## 4. Stack and boundaries

- One monorepo: React 18 + TypeScript + Vite at root, Spring Boot 4.1.3 / Java 21 under `backend/`, PostgreSQL 18.7, Maven Wrapper 3.9.16, Flyway SQL migrations, Spring JdbcClient and hand-written SQL. Node 24 via `.nvmrc`. Exact dependencies are in lockfiles/POM; do not update them casually.
- Modular monolith: site, cases, ledger, media, shared, development-only fixtures implemented; identity, audit, donations and contact planned. Preserve module boundaries; only ledger owns money movements. No Redis, Kafka, microservices or Kubernetes needed.
- Same-origin static frontend, `/api/*` and `/media/public/*`; backend on loopback port 8080 in dev. Hosting/domain deferred. Future HTTPS reverse proxy, private DB network, persistent volumes, backups and restore rehearsal.
- Dedicated public DTO allowlists, no serialization of private rows, parameterized SQL, repeatable-read snapshots per public response. PostgreSQL constraints protect critical invariants.
- Public contract remains v0.2.0 until M1A reviewed. Generated TypeScript DTOs, queries and endpoint paths live in `src/api/generated/public-api.ts`; run `cd contract && npm run generate:types`. Generator supports public GET schema subset today; extend it safely for M1. CI checks drift.
- Real mode never falls back to mock. Mock reads only sanitized public projection at `src/api/dataset.public.json`, byte-identical to `contract/fixtures/dataset.public.json`. Full private fixture stays server-side. Vite denies contract/backend/docs paths including raw and filesystem access; preserve this in future changes.

## 5. Approved financial and privacy invariants

All money is integer USD cents. One movement/goal 1..10^10 cents, gross platform movement sum <=10^15; totals remain safe JS integers. PostgreSQL numeric/JS BigInt cross-multiplication for exact funding-ratio comparisons.

- Ledger append-only. External confirmed donation increases allocation; refund (actual returned money) and donation reversal (erroneous original record, no money movement) decrease it. Transfers are atomic equal pairs, never external receipts or delivery. Disbursement increases delivered, disbursement reversal decreases it. Corrections reference original same-case entry of correct kind/date, cumulative amount capped, no correction of correction. Future writes need idempotency, original-entry locks and provider-reference duplicate checks.
- Case allocated/received = net external donations + net transfers in - net transfers out. Delivered = net disbursements; available = allocated - delivered. Platform received = net external only; platform delivered = net disbursements; available = received - delivered. Transfers cancel across cases. `peopleHelped` counts cases with positive delivery, not individuals.
- FUND is a pool; money must first transfer to an individual case. No direct FUND disbursement (DB and shared validator enforce).
- Forced refunds/real reversals remain visible even when balance goes negative. Show signed shortfall, clamp only visual progress; block discretionary spending that causes negative balance. Platform bound serialized by one counter row; concurrent cross-case inserts tested. More complex simultaneous corrections/transfer deadlocks/stronger isolation still need write-side tests.
- No money on drafts. Published cases never return to draft; keep financial history on withdrawal and redact consent-sensitive content. Current = active/funded; completed = delivered/closed; partial funding may complete. Goal changes can change active/funded status. Deadline ends acceptance, not status; funded cases accept until paused/deadline. Server `acceptingDonations` is authoritative.
- Deadlines are inclusive date + immutable IANA zone + exclusive next-day cutoff; default America/Chicago. Never timezone-shift date-only display. Event timestamps UTC whole seconds, shown in visitor's local timezone.
- Donor named/anonymous/hidden: named identity only with consent, avatar separate opt-in; anonymous null name/avatar; hidden not listed but counted. Net donor amount after corrections; zero disappears. No draft/private/hidden identity or credentials in public DTOs.
- Public media same-origin opaque tokens, only approved public types/owners/consent, withdrawn/unpublished 404; strip metadata/reencode in future upload pipeline. Public image cache max-age 300; public API no-cache/ETag; authenticated/private no-store. Withdrawal cannot erase downloaded copies. Production storage/upload still unimplemented.

## 6. Accepted baseline and evidence

M0B merge to main: `a7fbab22f837bdadc495d307b7fafdf1e83554ad`. PR [#6](https://github.com/abdoulbachir/APV/pull/6) contains [#4 frontend](https://github.com/abdoulbachir/APV/pull/4) and [#5 backend](https://github.com/abdoulbachir/APV/pull/5), both histories preserved and all marked merged. Main tree identical to final tested tree. Owner explicitly authorized merge after all checks passed.

Final accepted PR head `004e066aa9c5f34866d6a216edd2c553793f9b60`; Traycer live-browser tested `3d2c0cff31a76de6179a87f774bbd7f457f17dd5`. Final delta only favicon/HTML and sequential HTTP tests (parallel Vite port collision fixed); favicon automated check passed, Chromium retest not run on final delta.

Final CI: [frontend 8/8 tests, both builds, audits zero](https://github.com/abdoulbachir/APV/actions/runs/45535515057), [backend 114/114 (38 unit/architecture + 76 real PG)](https://github.com/abdoulbachir/APV/actions/runs/45535515006), [contract 1,806 schema-valid responses, 1,838 independent parity, type drift](https://github.com/abdoulbachir/APV/actions/runs/45535514982), [live API 114 comparisons, 10 errors, 4 image variants](https://github.com/abdoulbachir/APV/actions/runs/45535515000). All three applicable main post-merge checks passed. Traycer earlier tested 72 responsive combinations, 200 individual routes, corrected six findings; joint acceptance 24 browser combinations, 12 pixel-identical mock/real screenshots, no axe violations, successful backend outage/retry, real PostgreSQL via isolated rootless Docker. Chromium only; no production deployment, Firefox/WebKit or manual assistive-technology certification.

Full fictional dataset: 205 records (200 current, 2 completed, 2 drafts, FUND), 292 ledger entries, 9 category records (8 public incl inactive transport; retired draft-only excluded), 17 pages of 12 (last 8), bilingual. Golden checks: HELP-014 allocated 567500 delivered 320000; FUND available 164000 delivered 0; HELP-101 allocated 38500 delivered 30000, donor D3 net 3500; HELP-903 available -1000; drafts HELP-902/906 return case_not_found 404. Fixture media variants share original bytes in dev only. No real donation or account data.

## 7. Current work: M1A authentication and security contract

Branch `feature/m1a-identity-contract` was created from accepted main `a7fbab2`. At this handoff the branch still points there, no M1 PR exists and no identity implementation has been committed. Owner authorized M1; backend agent was given the M1A assignment in chat (sending/receipt not independently confirmed). Next action: inspect actual remote head and review backend's M1A report/contract before implementation. If it has not been sent, use this section as the assignment. Do not create conflicting changes on that agent's branch.

**Approved baseline:** same-origin PostgreSQL-backed server-side sessions, HttpOnly/Secure production cookie, SameSite=Lax, CSRF all mutations, default deny. Optional accounts, no self-elevation, guests can give/report in later milestone. The following M1 choices are proposals, not final owner decisions: Spring Security + compatible Spring Session JDBC; Argon2id; passwords min 15 Unicode code points, >=64 supported, bounded, block common/compromised locally, no arbitrary composition/truncation; staff MFA mandatory (prefer TOTP with hashed single-use recovery codes); operator provisioning and verified manual recovery without email initially; donor idle 2h/absolute 24h, staff idle 30m/absolute 12h. Verify dependencies and official security guidance; document tradeoffs before approval. No default staff credentials, no secrets in CLI arguments/logs, no permanent password assigned by admins.

Roles (visitor is unauthenticated, not DB role): donor/user accesses own private account and future own gifts only; admin creates/edits cases, updates, views donation reports, disbursement only with explicit canDisburse; super admin confirms/rejects, transfers, manages payment methods/staff, contact messages and audit. Future permissions not operational yet. Server checks resource ownership and current permission on every protected operation; frontend visibility is not security.

M1A deliverables:
- Extend OpenAPI with exact requests/responses/errors/session effects for proposed GET /api/auth/csrf, POST /api/auth/register, /api/auth/login, /api/auth/logout, GET /api/me, POST /api/auth/change-password, plus only necessary activation/MFA/recovery operations. Preserve seven public GET endpoints. Closed private account DTOs separate from public schemas. 401 unauthenticated, 403 forbidden, 405 unsupported recognized method, 429 Retry-After; no HTML redirects, no-store auth/private responses, no public ETag behavior. Generic auth errors and no enumeration; explain duplicate registration UX.
- Design email normalization/unique concurrent registration, unverified email state, immutable account identifiers, disabled accounts, safe minimal profiles; never link guest gifts merely by matching email. Public register only donor. No email service assumed. Define verified identity/activation/recovery procedure with short-lived single-use credentials, no staff MFA bypass, no last usable superadmin removal. Define secure session rotation, idle/absolute expiry, revocation on password/MFA/role/disable changes; incomplete MFA cannot access staff privileges.
- SPA CSRF obtaining/submitting/refreshing token before all mutations including login/logout/register; reject cross-session invalid tokens; same origin, no credentialed permissive CORS; safe JSON/body limits, headers, trusted proxy and loopback HTTP vs HTTPS rules. Rate limiting by identifier and trustworthy IP, avoid permanent lockout abuse, generic responses.
- Propose DB account/credential/role/session/MFA/activation/reset/rate-limit/audit schema; append-only security events without sensitive material. Keep identity separate from ledger. Dev fixture loader currently destructive: isolate from real DB and identity credentials; explicitly design safe interaction.
- Extend contract validation and generator for request bodies/new methods/auth; retain all public privacy checks, fixture and financial tests. Review threat model and test matrix: registration races, credential enumeration, session fixation/rotation/expiry/revocation, CSRF, resource ownership, mass assignment/privilege escalation, disable/role changes, MFA replay/recovery, rate limits, last superadmin, cache/cookies/leaks and public regressions. Real PG and real browser tests in M1B.
- Contract/design only: no backend migrations/controllers, frontend pages, donation/financial/case writes, profile uploads, production deployment or M1B until review. Push branch, draft PR, exact SHA, sources, decisions needing owner approval, limitations and tests. Frontend agent independently reviews contract and can open PR if backend cannot.

Open decisions include email/verification/recovery operating model, MFA enrollment and recovery, exact timeouts, data retention, guest tracking, future hosting/domain, production provider eligibility. Do not silently promote proposals into owner-approved decisions.

## 8. Roadmap and agent workflow

| Stage | State / intended scope |
|---|---|
| Phase 1 / M0A / M0B | Accepted and merged: design, contract, public reads and joint integration |
| M1A | Current: auth/security contract and owner decision review |
| M1B | After approval: backend identity/security/audit; frontend login/signup/me/password/MFA/activation/recovery and guards in existing design; independent security and joint integration |
| M2 | Donation reporting/confirmation/manual/refund lifecycle, private evidence and required storage |
| M3 | Remaining public pages, donor account/profile/history |
| M4 | Case administration, updates, media/consent |
| M5 | Financial administration, disbursements/transfers, payment config, users/contact/audit |
| M6 | Production E2E, deployment/security readiness, backup/restore |

Owner wants actual implementation and verification, not repeated planning. Frontend agent (ChatGPT/GitHub connector) owns frontend, shared fixture/type tooling and independent contract review; backend agent (local coding agent) owns Spring/DB and proposes contract; Traycer is independent tester, read-only review unless explicitly reassigned. Backend agent may lack GitHub API; frontend agent can create PRs. Do not extract stored git credentials or install GitHub CLI against owner instruction. Use connected GitHub account `abdoulbachir` for writes (not Nazir-code); discover available connector actions at runtime.

Use exact baseline SHAs, clean working trees, separate branches/worktrees, no force push. Contract-first, review pending owner decisions, then parallel frontend/backend, combined integration branch, test all suites and actual browser, obtain explicit owner authorization before merging main. Verify CI at exact final head, distinguish CI vs actual local tests, report limitations. Do not merge implementation PRs separately if already included in integration PR. Preserve main and other agents' work, no branch deletions without approval.

## 9. Practical commands

From local repo (usually `~/apv`), inspect `git status` first; then `git fetch origin --prune`, `git switch main`, `git pull --ff-only origin main` when clean. For separate work use worktrees or a clean branch. Node 24 / Java 21.

- Frontend: `npm ci && npm test && npm run build`; `npm audit`, `npm audit --omit=dev`; `VITE_API_MODE=real npm run build`.
- Contract: `cd contract && npm ci && npm run validate`.
- Backend: `cd backend && ./mvnw test` unit; `./mvnw verify` includes real PostgreSQL Testcontainers (Docker required).
- Mock preview: `npm run dev`; real preview: `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev`; production explicitly real and no sample-data flag.
- Local joint startup: from root only with isolated disposable DB and running Docker, `test -e .env || cp .env.example .env`, `docker compose up -d --wait`. In backend terminal: `cd backend; set -a; . ../.env; set +a; ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. In root: `node --experimental-strip-types tests/live-api.mjs`, then real preview. See linked docs for details. **Dev profile replaces fixture database contents every start. Never connect to production, overwrite existing .env or delete data volumes casually.** Rootless Docker was used successfully by Traycer; normal Docker Desktop still not confirmed. Flyway/PostgreSQL combination tested, formal vendor support unverified.

## 10. Resuming in a new chat

Read this handoff and the source documents, verify current main and M1A branch/PR, and review the backend agent's latest M1A report. Do not repeat already settled M0 decisions. The immediate goal is to finish and approve M1A, not implement M1B ahead of the contract.

After each milestone, update this file with the new accepted baseline, current checkpoint, important decisions, evidence, open questions and limitations. Archive superseded details in Git history or their appropriate ADR/contract files rather than expanding this summary indefinitely.
