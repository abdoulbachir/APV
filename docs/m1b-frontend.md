# M1B frontend implementation — work in progress

Baseline: `a6c39b217ca3f8d77befcbb32cc3664cc8dafcaa`. Backend PR #10 and frontend PR #11 are separate drafts.

## Foundation

The identity transport uses generated operation paths, request/response types and compile-time checked methods/status codes. All requests are same-origin, no-store, and reject redirects. No identity mock, browser persistence or automatic POST retries. Null-body operations omit Content-Type and body. Error text is a stable code, not an arbitrary server body.

`readIdentitySession` discovers state through GET /me, with three expected 401 state codes distinguished from secret failures. `prepareIdentityForm` acquires the in-memory CSRF token before reading /me; the resulting form context must be tied to that screen and invalidated on identity changes. Never silently acquire a fresh token while submitting an already-entered secret. `submitIdentityOnce` sends one POST: stale CSRF reconciles with fresh token then /me; lost-session and already-signed-in errors reconcile with /me; uncertain transport/server outcomes reconcile without replay. Secret failures do not sign out. The screen must clear secrets, invalidate stale contexts and render the reconciled state, including when pending states look identical. Successful transitions discard the prior form context. Recovery codes/secret responses must remain page-local, never in shared session state. The actual state provider, form lifecycle, cross-tab/bfcache guards and user-facing guidance are not implemented by this transport slice.

## Verification

The intercepted HTTP transport tests cover all 12 operations, 9 stale-token refusals, no replay, state mapping, bodyless POSTs, and sanitized errors. They are not browser or backend authentication acceptance evidence. Theme initializer is now an external blocking script; complete page CSP and browser verification remain required.

Next: provider and guarded routes, EN/FR forms and MFA/enrollment/recovery flows, local QR, exact production CSP and actual browser tests against backend. Preserve approved public routes, fixture isolation, financial behavior and accessibility.
