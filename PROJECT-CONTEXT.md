# APV-GAKASINAY — Project context and handoff

**Checkpoint:** 2026-11-25, M0B, M1A, generated fixture cleanup and M1B (identity runtime) accepted and merged. M2 not started.
**Purpose:** Start a fresh conversation or onboard an agent without replaying the development history.
**Repository:** https://github.com/abdoulbachir/APV

This is a living orientation document, not a replacement for the API contract, approved decisions or implementation. The commit references below are a dated checkpoint, not a claim about the current remote heads. Always fetch and verify the actual branch/PR status before acting. Update this document after accepted milestones; distinguish approved decisions, implementation evidence, proposals and unresolved questions. Never put credentials or real beneficiary/donor information here.

## 1. Product and scope

APV-GAKASINAY is a small, trusted friends/family aid initiative. The public website presents individual assistance cases, the general fund, progress, donor listings and a transparent financial record. Visitors often arrive from group-chat links on phones. It is bilingual English/French, mobile-first and usable without technical knowledge.

The project records money transferred **outside** the website. It does not process payments or collect bank credentials, card numbers, OTPs or payment passwords. An account is optional for giving or reporting a gift. The initiative is not a registered charity; gifts are not tax-deductible. Do not claim charity status or provider eligibility. Real funds, donor information and beneficiary evidence require privacy controls.

Future payment methods: Zelle, PayPal, Cash App, Apple Cash (person-to-person, not Apple Pay), plus Other leading to a contact form. Methods are administrator-configurable records, with versioned recipient instructions/details, active status, ordering, bilingual instructions and audit history. No redeployment to add another compatible method. Verify provider terms/account/geographic eligibility before production. Never automatically confirm a gift from a screenshot. Donor reports are pending until the super administrator verifies actual receipt; reported and confirmed amounts are separate. Optional screenshots are private supporting evidence. Guest donation tracking and retention remain open.

## 2. Authoritative sources

- [Approved decisions](docs/decisions.md): overrides historical "pending" language for decisions actually approved.
- [API v0.3.0](contract/openapi.yaml) on main: seven accepted public GET endpoints and twelve identity operations, all implemented since M1B; preserve compatibility.
- [Identity and security contract](contract/identity-and-security.md), [ADR 0002](docs/adr/0002-identity-and-sessions.md), [M1A review](contract/M1A-review.md), [security acceptance matrix](contract/tests/security-acceptance.md): authoritative accepted design and the 220 M1B scenarios. Section 4.3 of the identity contract was reworded on 2026-11-25 with owner approval (version unchanged). These paths refer to the current main tree, not the older base of this documentation branch.
- [M1B acceptance evidence](docs/m1b-acceptance-evidence.md): every finding, correction, reviewer round and known limit of M1B, with the scenario map. [Backend scenario coverage](docs/m1b-backend-acceptance-coverage.md), [frontend notes](docs/m1b-frontend.md), [browser acceptance harness](tests/acceptance/README.md).
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
- Contract v0.3.0 accepted. Generated TypeScript DTOs and operation paths live in `src/api/generated/public-api.ts` and `src/api/generated/identity-api.ts`; run `cd contract && npm run generate:types`. Generator supports identity bodies, state metadata and unions. CI checks drift.
- Real mode never falls back to mock. PR #9 removed the three large generated datasets from Git tracking. `npm run fixtures:generate` writes them locally from deterministic inputs using Node standard-library dependencies. Mock reads only the sanitized generated public copy at `src/api/dataset.public.json`, byte-identical to `contract/fixtures/dataset.public.json`; full private data stays server-side. Vite denies contract/backend/docs paths including raw/filesystem access. Default `npm run build` is real, with no generated fixtures required or bundled; `npm run build:mock` is an explicit fictional preview. Preserve these boundaries.

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

## 7. Accepted M1A, cleanup and M1B

### Accepted checkpoints

- M1A: [PR #8](https://github.com/abdoulbachir/APV/pull/8), independent Traycer PASS at `b5ea149fb3e33380dd622ad734117a15051b4bfd`. Owner approved all seven policies and operational advice. Documentation closure `458e87fd9d1aab4dff15c3c76b8582079021ec3c`, all four CI workflows passed; merged to main at `daa1637a703437fcd132fe4323909782b8a95eed`. No identity runtime implemented. All 220 security scenarios are planned, not executed authentication tests.
- Generated fixture cleanup: [PR #9](https://github.com/abdoulbachir/APV/pull/9), accepted head `46273e9037828b4b6c6e5ffae3f2db63d8ba2482`, independent PASS and all four CI workflows passed; owner authorized merge, main advanced to `a6c39b217ca3f8d77befcbb32cc3664cc8dafcaa`. See [independent acceptance](https://github.com/abdoulbachir/APV/pull/9#issuecomment-7152910529). All three generated files absent from tracked tree; exact fixture blob digests unchanged on generation. Independent evidence: 8 frontend tests, 38 backend unit + 76 real PostgreSQL integration tests, 1,806 schema-valid responses, 1,838 parity matches, 114 live comparisons, 10 errors, 4 media assets; clean real builds with/without fixtures and explicit mock build, real dist/JAR marker inspection, Chromium at 1440x900 and 390x844; backend outage has no fallback. Linux filesystem only; no production deployment or real data. Post-merge CI was queued at that checkpoint.
- M1B: [PR #12](https://github.com/abdoulbachir/APV/pull/12) (`integration/m1b-identity`, containing the frontend and backend branches of PRs #10 and #11 with both histories). Accepted head `96d78a16bffee96f6f0b059c3e39aec44b908828`, all four CI workflows passed there; owner authorized the merge; main advanced to `d296ac6cd3ec4ae5e33afb0735a8b12221cfbc0b` by a merge commit, tree identical to the accepted head. The three workflows that run on main passed after the merge. PRs #10 and #11 show as merged because their commits arrived through #12; they were not merged separately.

### Approved identity policy

See current main `docs/decisions.md` and contract v0.3.0. Registration closed in production until verified email, open only for intentional dev/test coverage. Guest giving/reporting remains account optional. Donor session 2h idle/24h absolute; staff 30min/12h. Mandatory staff TOTP with ten single-use recovery codes. Operator staff provisioning and recovery until M5; independently established contact channel required, refuse if identity cannot be verified. Minimum 15 NFC-normalized Unicode code points, local blocklist, no composition rules. ASCII-only email initially. Adopted operational advice: two trusted usable super administrators when practical plus secure offline recovery codes, not an enforced two-admin invariant.

Same-origin PostgreSQL sessions, HttpOnly/Secure production cookies, SameSite=Lax; session-bound CSRF and deterministic request precedence. Never automatically replay a refused mutation. Distinguish secret failures from session loss and pending MFA states. Only own-account read/change-password permissions operational in M1; future staff/financial permissions remain non-operational. No new financial features or identity linking from email alone. Preserve last usable superadmin invariant and explicit disposable-database marker design. No credentials or private identity state in browser storage.

### M1B as accepted

The twelve identity operations run on Spring Boot with PostgreSQL sessions, with sign-in, sign-up (closed in production), account, password, activation, second-factor and recovery-code screens in the frontend, EN/FR. Full record: `docs/m1b-acceptance-evidence.md`. Summary of the evidence, all on 2026-11-25:

- Backend suite: 332 tests in CI, including the backend-asserted scenarios of the 220 (`docs/m1b-backend-acceptance-coverage.md`). Frontend: 32 tests, both builds.
- Joint browser run (real frontend build, real backend, real PostgreSQL 18.7 in disposable containers, Chromium headless, dev profile and production rules over HTTPS): the 42 browser scenarios, 79 recorded checks PASS, 2 PARTIAL, 0 FAIL on the final application code (`8a21b2e`; later commits changed only tests and documents).
- Independent review, four rounds by a separate agent session with its own environment and inputs. Found and saw corrected: findings 2 to 5 of the joint run, D1, D2, D4, D5, D6, R1, R2 (introduced by the R1 correction, medium, a sign-out with two session cookies could end another person's session), R3. Final verdict at `bf8c33d`: acceptable, no new defect, not a statement of production readiness.
- Owner decisions during M1B: D3 accepted as a known limitation; both contract 4.3 wording clarifications approved; a hint added to the set-password refusal message in both languages; R1 to be fixed rather than accepted.

Known limits, none of them hidden by the acceptance:

- D3: a password change can sign out its own author when another request of that session is in flight (ADR 0002).
- Scenarios 10.15 and 10.16 are PARTIAL: no real screen reader and no real password manager were used. Chromium only.
- Not all 220 scenarios ran in a browser; most are asserted by the backend suite only. Real-time expiry was simulated by ageing stored sessions.
- The breached-password list used in tests is synthetic, of production size. A real corpus must be supplied for production; the optional SHA-256 pin exists for it.
- The database role that owns the tables can remove the TRUNCATE protection; a separate non-owning application role is a deployment decision. Tables without a row-level rule can be truncated. Before the first operator command a real database is not yet marked real.
- A request carrying several session cookies is not refused; it acts only on the first value that names a live session, whose token it must show. In production the `__Host-` prefix prevents two.
- No production deployment, hosting, TLS termination, email or real data.

Lessons worth keeping: a correction to session handling needs an adversarial check, not only a code reading (R2 passed the coordinator's reading and was found by attack); tests that assert on captured logs must match log records, and a test class that raises log levels must restore them.

Open later decisions: email infrastructure and automated verification/recovery, screenshot retention, guest tracking, hosting/domain, provider eligibility, the application's database role in production. Do not reopen approved M0/M1A/M1B design or introduce later milestone functionality.

## 8. Roadmap and agent workflow

| Stage | State / intended scope |
|---|---|
| Phase 1 / M0A / M0B | Accepted and merged: design, contract, public reads and joint integration |
| M1A | Accepted and merged: identity/security contract and owner policy decisions |
| Cleanup | Accepted and merged: generated fixture lifecycle and artifact isolation |
| M1B | Accepted and merged: identity runtime, backend security and audit, frontend sign-in and account flows, joint browser acceptance, four independent review rounds |
| M2 | Next, not started; needs the owner's go-ahead. Donation reporting/confirmation/manual/refund lifecycle, private evidence and required storage |
| M3 | Remaining public pages, donor account/profile/history |
| M4 | Case administration, updates, media/consent |
| M5 | Financial administration, disbursements/transfers, payment config, users/contact/audit |
| M6 | Production E2E, deployment/security readiness, backup/restore |

Owner wants actual implementation and verification, not repeated planning. Since M1B the frontend agent is a local coding agent that is also technical coordinator and independent backend reviewer: it owns the frontend, shared fixture/type tooling, the integration branch and the acceptance harness, and never edits backend code (it reports defects). The backend agent (local coding agent, clone `~/apv`) owns Spring/DB and proposes contract changes. Independent acceptance is a separate read-only agent session with its own clone and containers; the Traycer product declined the M1B assignment three times, so a separate agent session did it. The owner relays prompts and reports between agents. Do not extract stored git credentials or install GitHub CLI against owner instruction. Use connected GitHub account `abdoulbachir` for writes (not Nazir-code); discover available connector actions at runtime.

Use exact baseline SHAs, clean working trees, separate branches/worktrees, no force push. Contract-first, review pending owner decisions, then parallel frontend/backend, combined integration branch, test all suites and actual browser, obtain explicit owner authorization before merging main. Verify CI at exact final head, distinguish CI vs actual local tests, report limitations. Do not merge implementation PRs separately if already included in integration PR. Preserve main and other agents' work, no branch deletions without approval.

## 9. Practical commands

From local repo (usually `~/apv`), inspect `git status` first; then `git fetch origin --prune`, `git switch main`, `git pull --ff-only origin main` when clean. For separate work use worktrees or a clean branch. Node 24 / Java 21.

- Frontend: `npm ci && npm test && npm run build`; `npm audit`, `npm audit --omit=dev`; `npm run test:production-build`. Default build real; explicit `npm run build:mock` for fictional preview.
- Contract: `cd contract && npm ci && npm run validate`.
- Backend: `cd backend && ./mvnw test` unit; `node ../contract/tools/generate-dataset.mjs && ./mvnw verify` includes real PostgreSQL Testcontainers (Docker required).
- Mock preview: `npm run dev`; real preview: `VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev`; production explicitly real and no sample-data flag.
- Local joint startup: from root only with isolated disposable DB and running Docker, `npm run fixtures:generate`, `test -e .env || cp .env.example .env`, `docker compose up -d --wait`. In backend terminal: `cd backend; set -a; . ../.env; set +a; ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. In root: `node --experimental-strip-types tests/live-api.mjs`, then real preview. See linked docs for details. **Dev profile replaces fixture database contents every start. Never connect to production, overwrite existing .env or delete data volumes casually.** Rootless Docker and Docker Desktop both work. Docker Desktop on the owner's machine is kept stopped: start it with `systemctl --user start docker-desktop`; before stopping it run `docker desktop stop` (with a timeout, it can hang), otherwise containers and volumes removed just before reappear at the next start. It refuses bind mounts from `/tmp`.
- Joint browser acceptance: `tests/acceptance/README.md` (creates its own two PostgreSQL containers, refuses to run if its names or ports are taken, never touches another database). Flyway/PostgreSQL combination tested, formal vendor support unverified.

## 10. Resuming in a new chat

Read this handoff and current main source documents, verify main and open branches/PRs. Do not repeat accepted M0, M1A, cleanup or M1B work. M2 has not started and starts only on the owner's instruction, contract first. Do not describe the identity runtime as production ready: see the known limits in section 7.

After each milestone, update this file with the new accepted baseline, current checkpoint, important decisions, evidence, open questions and limitations. Archive superseded details in Git history or their appropriate ADR/contract files rather than expanding this summary indefinitely.
