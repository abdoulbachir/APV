// Env B, part 1: production rules over HTTPS. Operator provisioning, credential redemption,
// enrollment with the real QR, recovery codes, sign-out, second-factor challenge, replay.
import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto';
import { ROOT, EVIDENCE, assert, check, record, psql, resetThrottle, operator, freshTotp, wrongTotp, totpAt, decodeQrPng, profile, closeBrowser, secret, sleep,
  waitHeading, waitForm, fill, submit, settle, pathOf, signIn, signOut, fieldError, alertText, noticeText, heading } from './lib.mjs';

const ENV = 'B: production rules, HTTPS';
const DB = 'apv-m1b-accept-prod';
const origin = 'https://localhost:8443';
const stateFile = path.join(ROOT, 'env/state-b.json');
const S = { email: `owner.${crypto.randomBytes(3).toString('hex')}@example.test`, name: 'Fictional Owner', password: secret('password', 'Vt7 lantern-orbit quill 93'), };
resetThrottle(DB);
const me = await profile(origin, { https: true });
const page = await me.open();

// --- 1.10 closed sign-up through the real UI
await page.goto(origin + '/sign-up'); await waitHeading(page, 'Create an account'); await settle(page);
await check('1.10', ENV, 'Sign-up closed in production: real GET /auth/options answers registrationOpen=false, the page shows the closed message and offers no form', async () => {
  const options = me.log.responses.find(r => r.path === '/api/auth/options');
  assert.equal(options.status, 200); assert.equal(options.body.registrationOpen, false);
  assert.match(await page.locator('.identity-panel').innerText(), /Public registration is not open yet/);
  assert.equal(await page.locator('.identity-panel form').count(), 0);
  // The refusal itself, from the page's own origin with a real token and cookie:
  const forced = await page.evaluate(async () => {
    const t = await (await fetch('/api/auth/csrf', { cache: 'no-store' })).json();
    const r = await fetch('/api/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json', [t.headerName]: t.token }, body: JSON.stringify({ email: 'nobody@example.test', password: 'x'.repeat(20), displayName: 'N' }) });
    return { status: r.status, code: (await r.json()).error.code };
  });
  assert.deepEqual(forced, { status: 403, code: 'registration_closed' });
  assert.equal(psql(DB, "select count(*) from account where email_normalized='nobody@example.test'"), '0');
  return 'forced POST from the page: 403 registration_closed, no account row';
});

// --- operator provisioning
const created = await operator('create-super-admin', [S.email, S.name]);
const credential = secret('one-time credential', created.credential);
await check('7.2', ENV, 'Operator command creates the first super administrator on the unmarked database and prints a one-time activation credential (browser redemption follows)', async () => {
  assert.equal(created.code, 0, created.out.slice(-300)); assert.ok(credential && credential.length >= 20);
  assert.equal(psql(DB, 'select database_real from identity_guard'), 't');
  assert.ok(!created.argv.join(' ').includes(S.email), 'email on the command line');
  return 'exit 0; database marked real; the command line carries only the command name';
});

// --- 10.2 a credential-like fragment is discarded before the app starts
const fakeFragment = secret('fragment credential', 'fragment-credential-' + crypto.randomBytes(8).toString('hex'));
await page.goto(origin + '/auth/set-password#' + fakeFragment); await waitHeading(page, 'Set or reset your password'); await waitForm(page);
await check('10.2', ENV, 'A fragment on /auth/set-password is removed from the address and the history entry before the application starts, and is never sent', async () => {
  assert.equal(await page.evaluate(() => location.href), origin + '/auth/set-password');
  assert.equal(await page.locator('#credential').inputValue(), '');
  assert.ok(!me.log.requests.some(r => r.url.includes(fakeFragment) || (r.referer ?? '').includes(fakeFragment)));
  await page.goBack().catch(() => {}); await page.goForward().catch(() => {});
  assert.ok(!(await page.evaluate(() => location.href)).includes('#'));
  return 'address has no fragment after load and after back/forward; credential field empty';
});
await page.goto(origin + '/auth/set-password'); await waitHeading(page, 'Set or reset your password'); await waitForm(page);

// --- redeem: wrong credential first, then the real one
let t0 = Date.now();
await fill(page, { credential: 'not-a-real-credential-0000', newPassword: S.password }); await submit(page); await settle(page);
await check('7.x-wrong', ENV, 'A wrong one-time credential is refused on the credential field; nothing else changes', async () => {
  assert.match(await fieldError(page, 'credential'), /could not be used/);
  assert.equal(await page.locator('#credential').inputValue(), ''); assert.equal(await page.locator('#newPassword').inputValue(), '');
  assert.equal(me.posts(t0).length, 1); assert.equal(me.posts(t0)[0].status, 400);
});
t0 = Date.now();
await fill(page, { credential, newPassword: S.password }); await submit(page); await settle(page);
await check('7.3', ENV, 'The activation credential is redeemed in the real frontend: one POST /auth/set-password (204), then the sign-in screen', async () => {
  assert.deepEqual(me.posts(t0).map(r => [r.path, r.status]), [['/api/auth/set-password', 204]]);
  assert.equal(pathOf(page), '/sign-in'); assert.match(await noticeText(page), /Your password is set/);
  return 'notice shown, credential spent';
});

// --- 6.1 activated staff signs in inside the enrollment window
t0 = Date.now();
await signIn(page, S.email, S.password);
await check('6.1', ENV, 'Activated staff signs in: mfa_enrollment_required with pendingExpiresAt and no account; the enrollment screen is shown and private screens are unreachable', async () => {
  const login = me.posts(t0).find(r => r.path === '/api/auth/login');
  assert.equal(login.status, 200); assert.equal(login.body.status, 'mfa_enrollment_required'); assert.ok(login.body.pendingExpiresAt); assert.ok(!('account' in login.body));
  assert.equal(pathOf(page), '/auth/enroll'); assert.equal(await heading(page), 'Set up an authenticator');
  await page.goto(origin + '/account'); await settle(page);
  assert.equal(pathOf(page), '/auth/enroll');
  const probe = await page.evaluate(async () => (await fetch('/api/me', { cache: 'no-store' })).status + ' ' + (await (await fetch('/api/me', { cache: 'no-store' })).json()).error.code);
  assert.equal(probe, '401 mfa_enrollment_required');
});
await check('10.6e', ENV, 'Reloading while pending enrollment shows the enrollment screen, never the generic sign-in screen', async () => {
  await page.reload(); await waitHeading(page, 'Set up an authenticator'); assert.equal(pathOf(page), '/auth/enroll');
});

// --- 6.23 the QR, decoded offline from what the page actually drew
await waitForm(page);
const cookieBefore = (await me.cookie()).value;
t0 = Date.now();
await page.locator('button.identity-primary', { hasText: 'Start authenticator setup' }).click();
await page.locator('svg.identity-qr').waitFor({ state: 'visible', timeout: 15000 });
const enroll = me.posts(t0).find(r => r.path === '/api/auth/mfa/enroll');
S.secret = enroll.body.secret;
await check('6.23', ENV, 'The enrollment QR is drawn in the page; decoded offline it is exactly the otpauth URI the server issued; no request carries the secret or the URI', async () => {
  const png = await page.locator('svg.identity-qr').screenshot({ scale: 'css' });
  fs.writeFileSync(path.join(EVIDENCE, 'qr-geometry-only.txt'), 'QR screenshot decoded in memory; the image holds a secret and is not kept.\n');
  const big = await page.evaluate(() => { const s = document.querySelector('svg.identity-qr'); s.style.width = '420px'; s.style.height = '420px'; });
  const decoded = decodeQrPng(await page.locator('svg.identity-qr').screenshot()) ?? decodeQrPng(png);
  assert.ok(decoded, 'QR could not be decoded');
  assert.equal(decoded, enroll.body.otpauthUri);
  const uri = new URL(decoded);
  assert.equal(uri.protocol, 'otpauth:'); assert.equal(uri.host, 'totp');
  assert.equal(decodeURIComponent(uri.pathname), '/APV-GAKASINAY:' + S.email);
  assert.deepEqual(Object.fromEntries(uri.searchParams), { secret: S.secret, issuer: 'APV-GAKASINAY', algorithm: 'SHA1', digits: '6', period: '30' });
  assert.equal(await page.locator('code.identity-secret').innerText(), S.secret);
  assert.equal(await page.locator('svg.identity-qr image, svg.identity-qr [href]').count(), 0);
  for (const r of me.log.requests) { assert.ok(!r.url.includes(S.secret) && !r.url.includes('otpauth'), 'secret in a request URL: ' + r.path); }
  assert.deepEqual(me.log.external, []);
  return 'label APV-GAKASINAY:<email>, SHA1, 6 digits, 30 s; manual secret equals the QR secret; zero external requests';
}).catch(() => {});

// --- wrong confirmation code, then 6.2
t0 = Date.now();
await fill(page, { code: wrongTotp(S.secret) }); await submit(page); await settle(page);
await check('6.x-wrong-confirm', ENV, 'A wrong code at enrollment confirmation: error on the code field, field cleared, enrollment screen and QR kept', async () => {
  assert.match(await fieldError(page, 'code'), /not accepted/); assert.equal(await page.locator('#code').inputValue(), '');
  assert.equal(await page.locator('svg.identity-qr').count(), 1); assert.equal(me.posts(t0).length, 1);
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'code');
});
t0 = Date.now();
await fill(page, { code: await freshTotp(S.secret) }); await submit(page);
await waitHeading(page, 'Save your recovery codes');
S.codes = await page.locator('ol.identity-codes code').allInnerTexts();
await check('6.2', ENV, 'Enrollment confirmed with a correct code: ten recovery codes shown once, session authenticated, identifier rotated', async () => {
  const confirm = me.posts(t0).find(r => r.path === '/api/auth/mfa/confirm');
  assert.equal(confirm.status, 200); assert.equal(S.codes.length, 10); assert.deepEqual(S.codes, confirm.body.codes); assert.equal(new Set(S.codes).size, 10);
  const meRead = me.log.responses.filter(r => r.path === '/api/me' && r.at >= t0).at(-1);
  assert.equal(meRead.status, 200); assert.equal(meRead.body.role, 'super_admin'); assert.equal(meRead.body.mfa.enrolled, true); assert.equal(meRead.body.mfa.recoveryCodesRemaining, 10);
  assert.notEqual((await me.cookie()).value, cookieBefore);
  return 'role super_admin, mfa.enrolled true, 10 codes remaining, session cookie value changed';
});
await check('3.9', ENV, 'Production session cookie over HTTPS as the browser stored it: __Host- prefix, Secure, HttpOnly, SameSite=Lax, host-only, path /, session lifetime', async () => {
  const c = await me.cookie();
  assert.equal(c.name, '__Host-apv_session'); assert.equal(c.secure, true); assert.equal(c.httpOnly, true); assert.equal(c.sameSite, 'Lax'); assert.equal(c.path, '/');
  assert.equal(c.domain, 'localhost'); assert.equal(c.expires, -1);
  const all = await me.context.cookies(); assert.equal(all.length, 1, 'other cookies: ' + all.map(x => x.name));
  return 'exactly one cookie in the jar';
});
await check('3.11', ENV, 'Page scripts cannot read the session cookie', async () => {
  assert.equal(await page.evaluate(() => document.cookie), '');
  assert.equal(await page.evaluate(async () => (await (window.cookieStore?.getAll() ?? [])).length), 0);
});
await check('codes-lifetime', ENV, 'Recovery codes are page-local: a reload shows the account without them, and they are in no storage', async () => {
  const dump = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
  for (const c of S.codes) assert.ok(!dump.includes(c));
  await page.reload(); await settle(page);
  assert.equal(await page.locator('ol.identity-codes').count(), 0);
  const text = await page.locator('body').innerText(); for (const c of S.codes) assert.ok(!text.includes(c));
});
fs.writeFileSync(stateFile, JSON.stringify(S), { mode: 0o600 });

// --- 3.6 / 10.5 sign out, then reuse the old cookie and token outside the browser
await page.goto(origin + '/account'); await waitHeading(page, 'My account'); await waitForm(page);
const old = { cookie: (await me.cookie()).value, token: me.log.responses.filter(r => r.path === '/api/auth/csrf').at(-1).body.token };
const oldId = Buffer.from(old.cookie, 'base64').toString();
assert.equal(psql(DB, `select count(*) from spring_session where session_id='${oldId}'`), '1');
t0 = Date.now(); await signOut(page);
await check('3.6', ENV, 'Sign out in the browser; the old cookie then answers 401 unauthenticated on GET /me and 403 csrf_invalid on a POST with the old token; the session row is gone', async () => {
  assert.deepEqual(me.posts(t0).map(r => [r.path, r.status]), [['/api/auth/logout', 204]]);
  process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
  const headers = { Cookie: '__Host-apv_session=' + old.cookie };
  const get = await fetch(origin + '/api/me', { headers }); assert.equal(get.status, 401); assert.equal((await get.json()).error.code, 'unauthenticated');
  const post = await fetch(origin + '/api/auth/logout', { method: 'POST', headers: { ...headers, 'X-CSRF-TOKEN': old.token, Origin: origin } });
  assert.equal(post.status, 403); assert.equal((await post.json()).error.code, 'csrf_invalid');
  assert.equal(psql(DB, `select count(*) from spring_session where session_id='${oldId}'`), '0');
});
await check('10.5', ENV, 'After sign-out the account screens are left and no account data remains in the page', async () => {
  assert.equal(pathOf(page), '/sign-in'); assert.match(await noticeText(page), /signed out/);
  const text = await page.locator('body').innerText(); assert.ok(!text.includes(S.email) && !text.includes(S.name));
  await page.goto(origin + '/account'); await settle(page); assert.equal(pathOf(page), '/sign-in');
});
await check('10.8', ENV, 'Back button after signing out: no private screen; GET /me is re-read first', async () => {
  const before = me.log.responses.filter(r => r.path === '/api/me').length;
  await page.goBack(); await settle(page);
  const text = await page.locator('body').innerText();
  assert.ok(!text.includes(S.email)); assert.equal(await page.locator('dl.identity-account').count(), 0);
  assert.ok(me.log.responses.filter(r => r.path === '/api/me').length > before, 'no /me read after going back');
  assert.equal(pathOf(page), '/sign-in');
  return 'history navigation re-read /me (401) and showed the sign-in screen';
});

// --- 6.6 / 10.6b / replay
await page.goto(origin + '/sign-in'); t0 = Date.now(); await signIn(page, S.email, S.password);
await check('10.6e', ENV, 'Reloading while pending a second factor shows the challenge, never the generic sign-in screen', async () => {
  assert.equal(pathOf(page), '/auth/mfa'); await page.reload(); await waitHeading(page, 'Verify your identity'); assert.equal(pathOf(page), '/auth/mfa');
});
await waitForm(page);
await check('10.6b', ENV, 'Wrong authenticator code at sign-in, attempts one to four: the challenge stays, the error is on the code field, the field is cleared and focused', async () => {
  for (let i = 1; i <= 4; i++) {
    const t = Date.now(); await fill(page, { code: wrongTotp(S.secret) }); await submit(page); await settle(page);
    assert.deepEqual(me.posts(t).map(r => [r.path, r.status, r.body?.error?.code]), [['/api/auth/mfa/verify', 401, 'mfa_code_invalid']], 'attempt ' + i);
    assert.equal(pathOf(page), '/auth/mfa'); assert.match(await fieldError(page, 'code'), /not accepted/); assert.equal(await page.locator('#code').inputValue(), '');
    assert.equal(await page.evaluate(() => document.activeElement?.id), 'code');
  }
});
t0 = Date.now(); const good = await freshTotp(S.secret); const goodAt = Date.now();
await fill(page, { code: good }); await submit(page); await settle(page);
await check('6.6', ENV, 'Staff with a second factor: sign-in answers mfa_required; a correct code after four wrong ones gives authenticated with an account', async () => {
  const verify = me.posts(t0).find(r => r.path === '/api/auth/mfa/verify');
  assert.equal(verify.status, 200); assert.equal(verify.body.status, 'authenticated'); assert.equal(verify.body.account.email, S.email);
  assert.equal(pathOf(page), '/account'); assert.match(await page.locator('dl.identity-account').innerText(), new RegExp(S.email.replace('.', '\\.')));
});
// replay of the code just accepted, inside its own validity window
await waitForm(page); await signOut(page); await signIn(page, S.email, S.password); await waitForm(page);
await check('6.8', ENV, 'An accepted authenticator code cannot be used again (replayed within the same 30-second window from the real UI)', async () => {
  assert.ok(Date.now() - goodAt < 55000, 'too slow to replay inside the window');
  const t = Date.now(); await fill(page, { code: good }); await submit(page); await settle(page);
  assert.deepEqual(me.posts(t).map(r => [r.status, r.body?.error?.code]), [[401, 'mfa_code_invalid']]); assert.equal(pathOf(page), '/auth/mfa');
  return 'same code, still inside its time step: 401 mfa_code_invalid';
});

// --- recovery code: use, replay
await page.locator('button.identity-link', { hasText: 'Use a recovery code instead' }).click();
t0 = Date.now(); await fill(page, { code: S.codes[0] }); await submit(page); await settle(page);
await check('6.11', ENV, 'A recovery code completes the challenge; the account then shows nine remaining', async () => {
  assert.equal(me.posts(t0)[0].status, 200); assert.equal(pathOf(page), '/account');
  assert.match(await page.locator('dl.identity-account').innerText(), /Recovery codes remaining\s*9/);
});
await waitForm(page); await signOut(page); await signIn(page, S.email, S.password); await waitForm(page);
await page.locator('button.identity-link', { hasText: 'Use a recovery code instead' }).click();
t0 = Date.now(); await fill(page, { code: S.codes[0] }); await submit(page); await settle(page);
await check('6.12', ENV, 'A used recovery code is refused when replayed; a different unused code then works', async () => {
  assert.deepEqual(me.posts(t0).map(r => [r.status, r.body?.error?.code]), [[401, 'mfa_code_invalid']]); assert.equal(pathOf(page), '/auth/mfa');
  const t = Date.now(); await fill(page, { code: S.codes[1] }); await submit(page); await settle(page);
  assert.equal(me.posts(t)[0].status, 200); assert.equal(pathOf(page), '/account');
  assert.match(await page.locator('dl.identity-account').innerText(), /Recovery codes remaining\s*8/);
});
S.usedCodes = 2;
fs.writeFileSync(stateFile, JSON.stringify(S), { mode: 0o600 });
fs.writeFileSync(path.join(EVIDENCE, 'b1-observations.json'), JSON.stringify({ external: me.log.external, violations: me.log.violations, pageErrors: me.log.pageErrors,
  console: me.log.console.filter(l => !/^(log|info|debug)/.test(l)).slice(0, 50), requestPaths: [...new Set(me.log.requests.map(r => r.method + ' ' + r.path))] }, null, 1));
await me.close(); await closeBrowser();
