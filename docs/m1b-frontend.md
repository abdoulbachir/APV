# M1B frontend implementation — work in progress

Baseline: `a6c39b217ca3f8d77befcbb32cc3664cc8dafcaa`. Backend PR #10 and frontend PR #11 are separate drafts.

## Foundation

The identity transport uses generated operation paths, request/response types and compile-time checked methods/status codes. All requests are same-origin, no-store, and reject redirects. No identity mock, browser persistence or automatic POST retries. Null-body operations omit Content-Type and body. Error text is a stable code, not an arbitrary server body.

`readIdentitySession` discovers state through GET /me, with three expected 401 state codes distinguished from secret failures. `prepareIdentityForm` acquires the in-memory CSRF token before reading /me; the resulting form context must be tied to that screen and invalidated on identity changes. Never silently acquire a fresh token while submitting an already-entered secret. `submitIdentityOnce` sends one POST: stale CSRF reconciles with fresh token then /me; lost-session and already-signed-in errors reconcile with /me; uncertain transport/server outcomes reconcile without replay. Secret failures do not sign out. The screen must clear secrets, invalidate stale contexts and render the reconciled state, including when pending states look identical. Successful transitions discard the prior form context. Recovery codes/secret responses must remain page-local, never in shared session state. The actual state provider, form lifecycle, cross-tab/bfcache guards and user-facing guidance are not implemented by this transport slice.

## Verification

The intercepted HTTP transport tests cover all 12 operations, 9 stale-token refusals, no replay, state mapping, bodyless POSTs, and sanitized errors. They are not browser or backend authentication acceptance evidence. Theme initializer is now an external blocking script; complete page CSP and browser verification remain required.

Next: provider and guarded routes, EN/FR forms and MFA/enrollment/recovery flows, local QR, exact production CSP and actual browser tests against backend. Preserve approved public routes, fixture isolation, financial behavior and accessibility.

## Session provider foundation

The in-memory session store, React provider and session boundary now exist. Revalidation drops stale account state synchronously, revokes form leases by generation, and ignores late responses from superseded requests. Preparing a form reads the token and state before accepting secrets, and rejects a lease if the known authority has changed. Visibility, pagehide/pageshow and BroadcastChannel invalidation hooks are implemented; the channel carries only a generic signal. A CSS guard hides guarded content synchronously before bfcache snapshots. No session state, token or form secret is persisted. The store tests cover invalidation, races, pending states, form preparation and failure closure.

The provider/boundary are not mounted on routes yet; this is intentional until localized auth routes and submission lifecycle are added. Future screen code must check the lease generation, never silently refresh a token on submission, notify other tabs after confirmed or uncertain session changes, and clear page-local secrets before applying reconciliation. Actual browser visibility/bfcache/cross-tab behavior and the complete CSP still require acceptance tests. Public routes are unchanged in this slice.

## First connected identity screens

Routes /sign-in, /sign-up, /auth/mfa, /auth/enroll and /account mount the provider only inside the identity subtree; public routes stay independent, with a footer entry link. Mock mode deliberately does not simulate identity. Sign-in, server-option-gated registration, TOTP/recovery challenge, account read and sign-out are connected to the real typed transport. /auth/enroll is a guarded development placeholder, not completed enrollment. Password change, credential redemption, QR and recovery-code display/regeneration remain upcoming. Stale reconciliation applies the returned state directly and carries a newly issued token into empty forms of the new generation without re-fetching on StrictMode remounts; no duplicate automatic mutation or identity request. Screen submission discards secret fields and handles outdated responses. Browser acceptance and real backend integration remain open.

All mutation controls share one store-level submission lock; ordinary secret failures focus the appropriate field and retain the session. The token lease is memory-only and is discarded on generation invalidation.

## Remaining identity operations connected

All twelve operation paths now have UI callers: enrollment start/confirm, local QR and manual secret, one-time recovery display, regeneration, password change and operator credential redemption. The QR encoder is the exact pinned MIT package `qrcode-generator@2.0.4` (no runtime dependencies); npm package metadata and independent lockfiles agree on the tarball integrity. CI must verify the install/audit. No external QR request, data URL or HTML injection. The matrix is rendered as SVG geometry with a four-module quiet zone. Credential entry is typed/pasted only; unexpected fragments on /auth/set-password are stripped before the app initializes, not interpreted as a credential link. Recovery receipts are page-local, visible only for the verified post-operation account and generation. A lost response prompts recovery or re-sign-in, never automatic replay. All current tests are transport/store/QR unit checks and CI builds; real browser decoding, password manager behavior, cross-tab races, strict CSP and end-to-end acceptance still need verification. No backend changes.

## Production page policy and browser smoke test

`security/content-policy.mjs` is the canonical policy. Both real and explicitly fictional static builds include a CSP meta fallback (no inline scripts/styles or other origins). The Vite production preview additionally sends the complete HTTP CSP, including `frame-ancestors 'none'`, and no-referrer, nosniff and DENY headers. Deployment must send these same HTTP headers on all SPA HTML responses including direct/deep links and fallback pages; a meta tag cannot enforce frame-ancestors. This is a deployment requirement, not a claim that a production host is configured. Assets are emitted as files rather than data URIs. The browser workflow runs against built dist using isolated, pinned Playwright tooling, intercepted identity API responses and actual HTTP CSP. It checks eight locale/theme/viewport combinations, ordinary and stale errors, registration closed/open, activation, password change, MFA/enrollment/recovery codes and backend unavailable UI. It does not substitute for the real backend, real cookies, QR decoding, screen reader or password-manager verification. The test dependency is isolated in CI and not added to the application or its lockfile.

First CSP-browser CI attempt at `8236f41` passed build/unit/fixture checks but stopped at Playwright system dependency installation: ubuntu-latest resolved to Ubuntu 26.04, unsupported by the pinned Playwright 1.56.1. Pin the frontend runner to ubuntu-24.04 and rerun; do not report browser acceptance from the failed attempt.

Verified checkpoint `fed88321fb5a240b08c4953791db2aabe8c26ecb`: all four CI workflows completed successfully. Frontend CI https://github.com/abdoulbachir/APV/actions/runs/45647194117 reports 32 tests, zero failures, eight intercepted Chromium journeys (en/fr, light/dark, 360/1440px), 72 production-CSP page loads, zero npm audit findings. This is not real-backend acceptance. Production cookie/HTTPS, cross-tab and bfcache, QR decoding, accessibility, password manager, all stale-token and lost-response scenarios remain joint verification work.

## Joint acceptance run and proxy correction (2026-11-25)

The first run of the real frontend against the real backend found that the development and preview proxy rewrote the `Host` header, so the backend's origin check refused every identity `POST` with 403 `forbidden`. `vite.config.ts` now forwards the browser's `Host` unchanged. The intercepted browser tests could not have found this; `tests/acceptance/` can. Results, the open backend findings and the assignment for independent review are in `docs/m1b-acceptance-evidence.md`. Milestone 1B is not accepted.
