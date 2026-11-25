// Env B, part 2: one enrolled super administrator. Regeneration, password change across three
// browsers, signed-in refusals, token lifetime, idle/absolute/pending expiry, fifth wrong code.
import fs from 'node:fs'; import path from 'node:path';
import { ROOT, EVIDENCE, assert, check, psql, resetThrottle, freshTotp, wrongTotp, profile, closeBrowser, secret, sleep, trail, staffSignIn, sessionIdOf,
  waitHeading, waitForm, fill, submit, settle, pathOf, signIn, signOut, fieldError, noticeText, heading } from './lib.mjs';

const ENV = 'B: production rules, HTTPS';
const DB = 'apv-m1b-accept-prod';
const origin = 'https://localhost:8443';
const stateFile = path.join(ROOT, 'env/state-b.json');
const S = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
secret('password', S.password); secret('authenticator secret', S.secret); S.codes.forEach(c => secret('recovery code', c));
const save = () => fs.writeFileSync(stateFile, JSON.stringify(S), { mode: 0o600 });
const ageIdle = (id, minutes) => psql(DB, `update spring_session set last_access_time = last_access_time - ${minutes * 60000}, expiry_time = expiry_time - ${minutes * 60000} where session_id='${id}'`);
const endAbsolute = id => psql(DB, `update spring_session_attributes set attribute_bytes = substring(attribute_bytes from 1 for 74) || int8send((extract(epoch from now())::bigint - 5)) where attribute_name='apv.absoluteDeadline' and session_primary_id=(select primary_id from spring_session where session_id='${id}')`);
resetThrottle(DB);

const A = await profile(origin, { https: true }); const a = await A.open('/sign-in');
await staffSignIn(a, S);

await check('3.2', ENV, 'Staff session lifetimes as the real /me reports them: 30 minutes idle, 12 hours absolute', async () => {
  const m = A.log.responses.filter(r => r.path === '/api/me' && r.status === 200).at(-1);
  const idle = (Date.parse(m.body.session.idleExpiresAt) - m.at) / 60000, abs = (Date.parse(m.body.session.absoluteExpiresAt) - m.at) / 3600000;
  assert.ok(idle > 29 && idle <= 30.1, 'idle minutes ' + idle); assert.ok(abs > 11.9 && abs <= 12.01, 'absolute hours ' + abs);
  return `idle ${idle.toFixed(1)} min, absolute ${abs.toFixed(2)} h`;
});

// --- 10.6d and regeneration
await a.goto(origin + '/account/recovery-codes'); await waitHeading(a, 'Generate new recovery codes'); await waitForm(a);
let t0 = Date.now(); await fill(a, { currentPassword: S.password, code: wrongTotp(S.secret) }); await submit(a); await settle(a);
await check('10.6d', ENV, 'Wrong code on recovery-code regeneration: error on the code field, still signed in on the same screen', async () => {
  assert.deepEqual(trail(A, t0), ['POST /auth/mfa/recovery-codes 401']); assert.match(await fieldError(a, 'code'), /not accepted/);
  assert.equal(pathOf(a), '/account/recovery-codes'); assert.equal(await a.evaluate(async () => (await fetch('/api/me', { cache: 'no-store' })).status), 200);
  assert.equal(await a.locator('#currentPassword').inputValue(), ''); assert.equal(await a.locator('#code').inputValue(), '');
});
t0 = Date.now(); await fill(a, { currentPassword: S.password, code: await freshTotp(S.secret) }); await submit(a); await waitHeading(a, 'Save your recovery codes');
const oldCodes = S.codes; S.codes = await a.locator('ol.identity-codes code').allInnerTexts(); S.codes.forEach(c => secret('recovery code', c)); save();
await check('6.17', ENV, 'Regeneration with password and a fresh code shows ten new codes once; the session continues; the account shows ten remaining', async () => {
  assert.equal(S.codes.length, 10); assert.ok(S.codes.every(c => !oldCodes.includes(c)));
  await a.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(a, 'My account');
  assert.match(await a.locator('dl.identity-account').innerText(), /Recovery codes remaining\s*10/);
  await a.goBack(); await settle(a); assert.equal(await a.locator('ol.identity-codes').count(), 0);
  return 'codes are not shown again after leaving the page (back button included)';
});

// --- 10.6a wrong current password
await a.goto(origin + '/account/password'); await waitHeading(a, 'Change password'); await waitForm(a);
const newPassword = secret('password', 'Qm4 harbor-violet kettle 58');
t0 = Date.now(); await fill(a, { currentPassword: 'definitely-not-the-password', newPassword }); await submit(a); await settle(a);
await check('10.6a', ENV, 'Wrong current password on the password-change screen: error on that field, still signed in on the same screen, GET /me 200', async () => {
  assert.deepEqual(trail(A, t0), ['POST /auth/change-password 401']); assert.match(await fieldError(a, 'currentPassword'), /could not sign you in/);
  assert.equal(pathOf(a), '/account/password'); assert.equal(await a.evaluate(() => document.activeElement?.id), 'currentPassword');
  assert.equal(await a.evaluate(async () => (await fetch('/api/me', { cache: 'no-store' })).status), 200);
});
await check('8.x-policy', ENV, 'New-password rules through the real UI: too short, on the breached list and equal to the current one are each refused on the new-password field; still signed in', async () => {
  const cases = [['short-pass-1', /too short/], ['breached-example-passphrase-2026', /different password/], [S.password, /different password/]];
  for (const [value, message] of cases) {
    const t = Date.now(); await fill(a, { currentPassword: S.password, newPassword: value }); await submit(a); await settle(a);
    assert.deepEqual(trail(A, t), ['POST /auth/change-password 400'], value === S.password ? 'same' : value); assert.match(await fieldError(a, 'newPassword'), message);
  }
  return 'the list consulted is the synthetic production-size file, not real breach data';
});

// --- 3.8: two more browsers signed in as the same account, then the change
const B = await profile(origin, { https: true }); const b = await B.open('/sign-in'); await staffSignIn(b, S);
const C = await profile(origin, { https: true }); const c = await C.open('/sign-in'); await staffSignIn(c, S);
await b.goto(origin + '/account/password'); await waitHeading(b, 'Change password'); await waitForm(b);   // B: a prepared form
await c.goto(origin + '/account'); await waitHeading(c, 'My account');                                    // C: a private screen
const aCookie = (await A.cookie()).value;
t0 = Date.now(); await fill(a, { currentPassword: S.password, newPassword }); await submit(a); await settle(a);
const oldPassword = S.password; S.password = newPassword; save();
await check('3.8', ENV, 'Password changed in browser A: A continues signed in on a rotated identifier; the other browsers are ended', async () => {
  assert.equal(A.posts(t0)[0].status, 204); assert.equal(pathOf(a), '/account'); assert.match(await noticeText(a), /password was changed/);
  assert.notEqual((await A.cookie()).value, aCookie); assert.equal(await a.evaluate(async () => (await fetch('/api/me', { cache: 'no-store' })).status), 200);
  assert.equal(psql(DB, `select count(*) from spring_session where principal_name is not null`), '1');
  return 'exactly one signed-in session row remains';
});
let tb = Date.now(); await fill(b, { currentPassword: oldPassword, newPassword: 'Another unused phrase 7741' }); await submit(b); await settle(b);
await check('10.6', ENV, 'Session lost while a private form is shown, then a POST (browser B): 403 csrf_invalid, one token fetch, GET /me 401, sign-in screen, the POST is not sent again', async () => {
  assert.deepEqual(trail(B, tb), ['POST /auth/change-password 403', 'GET /auth/csrf 200', 'GET /me 401']);
  assert.equal(B.posts(tb)[0].body.error.code, 'csrf_invalid'); assert.equal(pathOf(b), '/sign-in'); assert.match(await noticeText(b), /out of date|session ended/i);
  await sleep(1500); assert.equal(B.sentPosts(tb).length, 1);
  assert.ok(!(await b.locator('body').innerText()).includes(S.email));
});
await check('14.11', ENV, 'Follow-up after a POST on a session that no longer exists: exactly one GET /auth/csrf and one GET /me (401 unauthenticated), private state cleared, sign-in screen, nothing resent', async () => {
  const after = trail(B, tb); assert.equal(after.filter(x => x.startsWith('GET /auth/csrf')).length, 1); assert.equal(after.filter(x => x.startsWith('GET /me')).length, 1);
  assert.equal(B.log.responses.filter(r => r.at >= tb && r.path === '/api/me')[0].body.error.code, 'unauthenticated'); assert.equal(B.sentPosts(tb).length, 1);
  return 'session ended by a password change elsewhere rather than by sign-out; same server state (no session row)';
});
let tc = Date.now(); await c.reload(); await settle(c);
await check('10.6', ENV, 'Session lost while a private screen is shown, then a GET (browser C): 401 unauthenticated, private state cleared, sign-in screen', async () => {
  assert.ok(trail(C, tc).includes('GET /me 401')); assert.equal(pathOf(c), '/sign-in'); assert.ok(!(await c.locator('body').innerText()).includes(S.email));
});
await check('1.6', ENV, 'Sign-in with the old password after the change, and with an unknown address: the same refusal on the password field (two of the four cases; see env A for the UI comparison)', async () => {
  let t = Date.now(); await signIn(c, S.email, oldPassword); const one = C.posts(t)[0];
  t = Date.now(); await fill(c, { email: 'nobody.here@example.test', password: oldPassword }); await submit(c); await settle(c); const two = C.posts(t)[0];
  assert.equal(one.status, 401); assert.equal(two.status, 401); delete one.body.error.requestId; delete two.body.error.requestId; assert.deepEqual(one.body, two.body);
  assert.match(await fieldError(c, 'password'), /could not sign you in/);
});
await B.close(); await C.close();

// --- 3.14 fully signed in, calling the anonymous-only operations
await check('3.14', ENV, 'Fully signed in: /sign-in, /sign-up and /auth/set-password in the UI lead to the account; forced POSTs with a valid token answer 409 already_signed_in; the session is unchanged', async () => {
  for (const p of ['/sign-in', '/sign-up', '/auth/set-password']) { await a.goto(origin + p); await waitHeading(a, 'My account'); assert.equal(pathOf(a), '/account'); }
  const before = (await A.cookie()).value;
  const answers = await a.evaluate(async bodies => {
    const out = [];
    for (const [p, body] of bodies) {
      const t = await (await fetch('/api/auth/csrf', { cache: 'no-store' })).json();
      const r = await fetch(p, { method: 'POST', headers: { 'Content-Type': 'application/json', [t.headerName]: t.token }, body: JSON.stringify(body) });
      out.push(r.status + ' ' + (await r.json()).error.code);
    }
    return out;
  }, [['/api/auth/login', { email: 'x@example.test', password: 'p'.repeat(16) }], ['/api/auth/register', { email: 'x@example.test', password: 'p'.repeat(16), displayName: 'X' }], ['/api/auth/set-password', { credential: 'c'.repeat(20), newPassword: 'p'.repeat(16) }]]);
  assert.deepEqual(answers, Array(3).fill('409 already_signed_in')); assert.equal((await A.cookie()).value, before);
  assert.equal(await a.evaluate(async () => (await (await fetch('/api/me', { cache: 'no-store' })).json()).email), S.email);
  return 'register answers 409 before its closed-sign-up check, as the order of checks requires';
});

// --- 4.4 / 4.17 token lifetime and where it lives
await waitForm(a); await signOut(a);
await check('4.4', ENV, 'A token fetched before sign-in is refused after sign-in (403 csrf_invalid); a fresh token is accepted', async () => {
  const early = await a.evaluate(async () => (await (await fetch('/api/auth/csrf', { cache: 'no-store' })).json()).token);
  await a.goto(origin + '/sign-in'); await staffSignIn(a, S);
  const r = await a.evaluate(async early => {
    const post = t => fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': t }, body: JSON.stringify({ email: 'x@example.test', password: 'p'.repeat(16) }) }).then(async r => r.status + ' ' + (await r.json()).error.code);
    const fresh = (await (await fetch('/api/auth/csrf', { cache: 'no-store' })).json()).token;
    return [await post(early), await post(fresh), early === fresh];
  }, early);
  assert.deepEqual(r, ['403 csrf_invalid', '409 already_signed_in', false]);
  return 'the fresh token passes the token check and reaches the session-state answer';
});
await check('4.17', ENV, 'The CSRF token is in memory only: not in a cookie, storage, the address, the DOM or a global', async () => {
  const token = A.log.responses.filter(r => r.path === '/api/auth/csrf').at(-1).body.token;
  const found = await a.evaluate(async token => {
    const places = { cookie: document.cookie, local: JSON.stringify(Object.entries(localStorage)), session: JSON.stringify(Object.entries(sessionStorage)), url: location.href, dom: document.documentElement.outerHTML,
      globals: Object.keys(window).filter(k => { try { return typeof window[k] === 'string' && window[k].includes(token); } catch { return false; } }).join(), idb: JSON.stringify(await indexedDB.databases()) };
    return Object.entries(places).filter(([, v]) => v.includes(token)).map(([k]) => k);
  }, token);
  assert.deepEqual(found, []); assert.ok((await A.context.cookies()).every(c => !c.value.includes(token)));
});

// --- idle expiry (staff: 30 minutes), POST then GET
await a.goto(origin + '/account'); await waitHeading(a, 'My account'); await waitForm(a);
ageIdle(await sessionIdOf(A), 31); t0 = Date.now(); await signOut(a);
await check('3.3', ENV, 'Staff session idle for 31 minutes (stored session aged in the isolated database), then a POST: 403 csrf_invalid, GET /me 401, sign-in screen, nothing resent', async () => {
  assert.deepEqual(trail(A, t0), ['POST /auth/logout 403', 'GET /auth/csrf 200', 'GET /me 401']); assert.equal(pathOf(a), '/sign-in'); await sleep(1200); assert.equal(A.sentPosts(t0).length, 1);
  return 'time was simulated by moving the stored last-access time back; nobody waited 30 minutes';
});
await staffSignIn(a, S); ageIdle(await sessionIdOf(A), 29); t0 = Date.now(); await a.reload(); await settle(a);
await check('3.3', ENV, 'Staff session idle for 29 minutes: still signed in', async () => { assert.ok(trail(A, t0).includes('GET /me 200')); assert.equal(pathOf(a), '/account'); });
ageIdle(await sessionIdOf(A), 31); t0 = Date.now(); await a.reload(); await settle(a);
await check('3.3', ENV, 'Staff session idle for 31 minutes, then a GET: 401 unauthenticated and the sign-in screen', async () => { assert.ok(trail(A, t0).includes('GET /me 401')); assert.equal(pathOf(a), '/sign-in'); });

// --- absolute expiry
await staffSignIn(a, S); const absId = await sessionIdOf(A); endAbsolute(absId); t0 = Date.now(); await a.reload(); await settle(a);
await check('3.4', ENV, 'Session past its absolute deadline while in active use (stored deadline moved into the past): GET /me 401, sign-in screen', async () => {
  assert.ok(trail(A, t0).includes('GET /me 401'), trail(A, t0).join()); assert.equal(pathOf(a), '/sign-in');
  return 'activity does not extend it: the session had been used seconds earlier';
});

// --- 10.6f pending sign-in expires under the challenge
resetThrottle(DB);
await signIn(a, S.email, S.password); await waitHeading(a, 'Verify your identity'); await waitForm(a);
const pid = await sessionIdOf(A); endAbsolute(pid); psql(DB, `update pending_sign_in set expires_at = now() - interval '1 minute' where outcome is null or outcome = 'open'`);
t0 = Date.now(); await fill(a, { code: await freshTotp(S.secret) }); await submit(a); await settle(a);
await check('10.6f', ENV, 'Pending sign-in expired under the challenge, then a correct code is submitted: 403 csrf_invalid, token fetch, GET /me 401 unauthenticated, back at the password step, the code is not sent again', async () => {
  assert.deepEqual(trail(A, t0), ['POST /auth/mfa/verify 403', 'GET /auth/csrf 200', 'GET /me 401']); assert.equal(A.posts(t0)[0].body.error.code, 'csrf_invalid');
  assert.equal(pathOf(a), '/sign-in'); await sleep(1200); assert.equal(A.sentPosts(t0).length, 1);
  return 'expiry simulated by moving the stored deadlines into the past';
});

// --- 10.6c the fifth wrong code
await signIn(a, S.email, S.password); await waitHeading(a, 'Verify your identity'); await waitForm(a);
const pendingToken = A.log.responses.filter(r => r.path === '/api/auth/csrf').at(-1).body.token;
for (let i = 1; i <= 4; i++) { await fill(a, { code: wrongTotp(S.secret) }); await submit(a); await settle(a); }
t0 = Date.now(); await fill(a, { code: wrongTotp(S.secret) }); await submit(a); await settle(a);
await check('10.6c', ENV, 'Fifth wrong code: back at the password step with a message, nothing of the pending sign-in remains; a forced further code is refused and not resent', async () => {
  const seen = trail(A, t0); assert.equal(seen[0], 'POST /auth/mfa/verify 401'); assert.equal(A.posts(t0)[0].body.error.code, 'unauthenticated');
  assert.equal(pathOf(a), '/sign-in'); assert.match(await noticeText(a), /session ended/i); assert.equal(await a.locator('#code').count(), 0);
  assert.equal(psql(DB, `select count(*) from spring_session where principal_name is not null`), '0');
  const forced = await a.evaluate(async ([token, code]) => { const r = await fetch('/api/auth/mfa/verify', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token }, body: JSON.stringify({ method: 'totp', code }) }); return r.status + ' ' + (await r.json()).error.code; }, [pendingToken, await freshTotp(S.secret)]);
  assert.equal(forced, '403 csrf_invalid'); await sleep(1000); assert.equal(A.sentPosts(t0).filter(r => r.path.endsWith('/verify')).length, 2);
  return 'a correct code forced with the pending session\'s token: 403 csrf_invalid; the account is not locked';
});
resetThrottle(DB);
await check('6.19', ENV, 'After five wrong codes the account can still sign in normally; an old (regenerated-away) recovery code is refused', async () => {
  await signIn(a, S.email, S.password); await waitHeading(a, 'Verify your identity'); await waitForm(a);
  await a.locator('button.identity-link', { hasText: 'Use a recovery code instead' }).click();
  let t = Date.now(); await fill(a, { code: oldCodes[5] }); await submit(a); await settle(a); assert.equal(A.posts(t)[0].status, 401);
  await a.locator('button.identity-link', { hasText: 'Use an authenticator code instead' }).click();
  await fill(a, { code: await freshTotp(S.secret) }); await submit(a); await waitHeading(a, 'My account');
});
fs.writeFileSync(path.join(EVIDENCE, 'b2-observations.json'), JSON.stringify({ external: A.log.external, violations: A.log.violations, pageErrors: A.log.pageErrors, console: A.log.console.slice(0, 80) }, null, 1));
await A.close(); await closeBrowser();
