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
