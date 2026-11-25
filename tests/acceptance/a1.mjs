// Env A: dev profile (open sign-up, fictional fixtures, disposable database) behind the project's
// production preview server with its enforced HTTP policy. Donor journeys, public pages, presentation.
import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto';
import { ROOT, EVIDENCE, assert, check, record, psql, resetThrottle, profile, closeBrowser, secret, sleep, trail, staleTokenOnce, loseAnswerOnce, totpAt, findSecrets,
  waitHeading, waitForm, fill, submit, settle, pathOf, signIn, signOut, fieldError, noticeText } from './lib.mjs';
import { sweep, keyboard, fields, errorSemantics } from './ui-checks.mjs';

const ENV = 'A: dev profile, HTTP preview with enforced policy', DB = 'apv-m1b-accept-dev', origin = 'http://127.0.0.1:4173';
const devLog = fs.readFileSync(path.join(EVIDENCE, 'backend-dev.log'), 'utf8');
const admin = devLog.match(/super_admin\s+dev\.owner@example\.test\s+password: (\S+)\s+authenticator secret: (\S+)/);
const PINNED = Date.parse('2026-11-24T12:00:00Z') / 1000;
const D = { email: `donor.${crypto.randomBytes(3).toString('hex')}@example.test`, name: 'Fictional Donor', password: secret('password', 'Gp8 saffron-ridge otter 2291') };
resetThrottle(DB);
// The dev profile pins the application clock, so its rate-limit windows never end (see the dev-clock finding).
// The counters of this disposable database are therefore cleared while the presentation sweep reloads pages.
const keepClear = setInterval(() => { try { resetThrottle(DB); } catch { /* next tick */ } }, 4000);
const P = await profile(origin); const page = await P.open();
const sweeps = [], keys = [], fieldChecks = [], errorChecks = [];

// ---- headers of the document itself
await check('10.9-headers', ENV, 'Every application page, including deep links, is served with the enforced policy and framing headers', async () => {
  for (const p of ['/', '/cases', '/sign-in', '/account', '/auth/set-password', '/no/such/page']) {
    const r = await fetch(origin + p); const h = r.headers;
    assert.equal(h.get('content-security-policy'), "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'", p);
    assert.equal(h.get('x-frame-options'), 'DENY'); assert.equal(h.get('referrer-policy'), 'no-referrer'); assert.equal(h.get('x-content-type-options'), 'nosniff');
    const html = await r.text(); assert.ok(!/<script(?![^>]*\bsrc=)[^>]*>/i.test(html), 'inline script in ' + p); assert.ok(!/<style|\sstyle=/i.test(html), 'inline style in ' + p);
  }
  return 'served by the project\'s preview server; a production host must send the same headers (not yet configured anywhere)';
});

// ---- 10.9a theme before first paint, by the self-hosted script alone
await check('10.9a', ENV, 'The colour theme is applied by the self-hosted initializer before the application script runs, in both themes, with no inline script', async () => {
  const q = await profile(origin); const p = await q.open();
  await q.context.route('**/assets/*.js', route => route.abort());            // the application never starts
  for (const theme of ['dark', 'light']) {
    await p.goto(origin + '/sign-in'); await p.evaluate(t => localStorage.setItem('apv-theme', t), theme); await p.reload({ waitUntil: 'domcontentloaded' });
    assert.equal(await p.locator('html').getAttribute('data-theme'), theme);
  }
  assert.deepEqual(q.log.violations, []); await q.close();
  return 'with the application bundle blocked, html[data-theme] is already set from theme-init.js';
});

// ---- framing
await check('9.x-framing', ENV, 'The application cannot be framed by another origin', async () => {
  const q = await profile('http://localhost:4173'); const p = await q.open();
  await p.route('http://localhost:4173/frame-host', r => r.fulfill({ contentType: 'text/html', body: `<iframe id="f" src="${origin}/sign-in" width="600" height="400"></iframe>` }));
  await p.goto('http://localhost:4173/frame-host'); await sleep(1500);
  const frame = p.frames().find(f => f !== p.mainFrame());
  const inside = frame ? await frame.evaluate(() => document.querySelector('.identity-panel') ? 'rendered' : 'blocked').catch(() => 'blocked') : 'blocked';
  assert.equal(inside, 'blocked'); await q.close();
  return 'an iframe on http://localhost:4173 (another origin) pointing at the sign-in page shows nothing';
});

// ---- sign-up: stale token, lost answer, then 1.1
await page.goto(origin + '/sign-up'); await waitHeading(page, 'Create an account'); await waitForm(page);
fieldChecks.push(await fields(page, 'sign-up', { displayName: 'name', email: 'username', password: 'new-password' }));
keys.push(await keyboard(page, 'sign-up'));
sweeps.push(await sweep(P, page, 'sign-up')); await waitForm(page);
let stop = await staleTokenOnce(P, '/api/auth/register'); let t0 = Date.now();
await fill(page, { displayName: D.name, email: D.email, password: D.password }); await submit(page); await settle(page); await sleep(1500); await stop();
await check('4.14', ENV, 'Stale token on sign-up: one POST reaches the server (403 csrf_invalid), then one GET /auth/csrf and one GET /me, the out-of-date message, fields cleared, no resend, no account created', async () => {
  assert.deepEqual(trail(P, t0).slice(0, 3), ['POST /auth/register 403', 'GET /auth/csrf 200', 'GET /me 401']); assert.equal(P.sentPosts(t0).length, 1);
  assert.match(await noticeText(page), /out of date\. Your request was not repeated/);
  assert.ok((await page.locator('.identity-panel input').evaluateAll(e => e.map(x => x.value))).every(v => v === ''));
  assert.equal(psql(DB, `select count(*) from account where email_normalized='${D.email}'`), '0');
});
await waitForm(page);
const lostSignUp = await loseAnswerOnce(P, '/api/auth/register'); t0 = Date.now();
await fill(page, { displayName: D.name, email: D.email, password: D.password }); await submit(page); await settle(page); await sleep(1500); await lostSignUp.stop();
await check('3.17', ENV, 'Answer of sign-up dropped after the server created the account: the page says the result could not be confirmed and to try signing in; the sign-up is not sent again', async () => {
  assert.equal(lostSignUp.status, 202); assert.equal(P.sentPosts(t0).length, 1); assert.match(await noticeText(page), /could not be confirmed\. Try signing in before registering again/);
  assert.equal(psql(DB, `select count(*) from account where email_normalized='${D.email}'`), '1');
  return 'after: ' + trail(P, t0).join(' , ');
});
await check('4.15', ENV, 'After the stale refusal the sign-up was submitted again and accepted with the new token (202)', async () => { assert.equal(lostSignUp.status, 202); });
// a second, undisturbed sign-up for 1.1
const D2 = { email: `donor.${crypto.randomBytes(3).toString('hex')}@example.test`, name: 'Second Fictional Donor', password: secret('password', 'Hs4 nimbus-cedar falcon 7350') };
await page.goto(origin + '/sign-up'); await waitHeading(page, 'Create an account'); await waitForm(page);
t0 = Date.now(); await fill(page, { displayName: D2.name, email: D2.email, password: D2.password }); await submit(page); await settle(page);
await check('1.1', ENV, 'Sign up with a new address in the real UI, then sign in: 202 accepted and not signed in by sign-up; sign-in 200 authenticated; the account is a donor with an unverified address', async () => {
  const reg = P.posts(t0)[0]; assert.equal(reg.status, 202); assert.deepEqual(reg.body, { status: 'accepted' }); assert.equal(pathOf(page), '/sign-in'); assert.match(await noticeText(page), /account is ready\. Sign in/);
  assert.equal(P.log.responses.filter(r => r.at >= t0 && r.path === '/api/me').at(-1).status, 401);
  const t = Date.now(); await signIn(page, D2.email, D2.password); const login = P.posts(t)[0]; assert.equal(login.status, 200); assert.equal(login.body.status, 'authenticated'); assert.equal(login.body.account.email, D2.email);
  await waitHeading(page, 'My account'); const m = P.log.responses.filter(r => r.path === '/api/me' && r.status === 200).at(-1).body;
  assert.equal(m.role, 'donor'); assert.equal(m.emailVerified, false); assert.match(await page.locator('dl.identity-account').innerText(), /Donor[\s\S]*Not verified/);
  const idle = (Date.parse(m.session.idleExpiresAt) - PINNED * 1000) / 3600000, abs = (Date.parse(m.session.absoluteExpiresAt) - PINNED * 1000) / 3600000;
  assert.equal(idle, 2); assert.equal(abs, 24);
  return 'donor lifetimes reported as 2 h idle and 24 h absolute (measured from the dev profile\'s pinned clock)';
});
await check('3.11', ENV, 'Dev cookie: HttpOnly, SameSite=Lax, host-only, not readable by page scripts (not Secure on plain HTTP, by design of the dev profile)', async () => {
  const c = await P.cookie(); assert.equal(c.name, 'apv_session'); assert.equal(c.httpOnly, true); assert.equal(c.sameSite, 'Lax'); assert.equal(await page.evaluate(() => document.cookie), '');
});

// ---- signed-in donor screens
await waitForm(page); sweeps.push(await sweep(P, page, 'account (donor)', { allowSame: [D2.email, D2.name] })); keys.push(await keyboard(page, 'account (donor)'));
await page.goto(origin + '/account/recovery-codes'); await settle(page);
await check('6.16', ENV, 'A donor opening the recovery-codes screen is told the request is refused and offered no form', async () => {
  assert.match(await page.locator('.identity-panel [role=alert]').innerText(), /refused/); assert.equal(await page.locator('.identity-panel form').count(), 0);
});
await page.goto(origin + '/account/password'); await waitHeading(page, 'Change password'); await waitForm(page);
fieldChecks.push(await fields(page, 'change password', { currentPassword: 'current-password', newPassword: 'new-password' }));
keys.push(await keyboard(page, 'change password')); sweeps.push(await sweep(P, page, 'change password')); await waitForm(page);
await fill(page, { currentPassword: 'wrong wrong wrong wrong', newPassword: 'Uq3 plover-granite dune 6642' }); await submit(page); await settle(page);
errorChecks.push(await errorSemantics(page, 'change password, wrong current password', 'currentPassword'));
await fill(page, { currentPassword: D2.password, newPassword: 'short' }); await submit(page); await settle(page);
errorChecks.push(await errorSemantics(page, 'change password, new password too short', 'newPassword'));
await page.goto(origin + '/account'); await waitHeading(page, 'My account'); await waitForm(page); await signOut(page);

// ---- anonymous screens
await waitHeading(page, 'Sign in'); await waitForm(page);
fieldChecks.push(await fields(page, 'sign-in', { email: 'username', password: 'current-password' })); keys.push(await keyboard(page, 'sign-in')); sweeps.push(await sweep(P, page, 'sign-in')); await waitForm(page);
await check('10.14-enter', ENV, 'Sign-in works with the keyboard alone: type, Tab, Enter', async () => {
  await page.locator('#email').focus(); await page.keyboard.type(D2.email); await page.keyboard.press('Tab'); await page.keyboard.type('not the password at all'); const t = Date.now(); await page.keyboard.press('Enter'); await settle(page);
  assert.equal(P.posts(t)[0].status, 401);
});
errorChecks.push(await errorSemantics(page, 'sign-in, wrong password', 'password'));
await page.goto(origin + '/auth/set-password'); await waitHeading(page, 'Set or reset your password'); await waitForm(page);
fieldChecks.push(await fields(page, 'set password', { credential: 'off', newPassword: 'new-password' })); keys.push(await keyboard(page, 'set password')); sweeps.push(await sweep(P, page, 'set password')); await waitForm(page);
await fill(page, { credential: 'no-such-credential-000000', newPassword: 'Uq3 plover-granite dune 6642' }); await submit(page); await settle(page);
errorChecks.push(await errorSemantics(page, 'set password, wrong credential', 'credential'));

// ---- the second-factor challenge (dev super administrator; codes follow the pinned dev clock)
resetThrottle(DB);
await page.goto(origin + '/sign-in'); await signIn(page, 'dev.owner@example.test', secret('password', admin[1])); secret('authenticator secret', admin[2]);
await waitHeading(page, 'Verify your identity'); await waitForm(page);
fieldChecks.push(await fields(page, 'second-factor challenge', { code: 'one-time-code' })); keys.push(await keyboard(page, 'second-factor challenge')); sweeps.push(await sweep(P, page, 'second-factor challenge')); await waitForm(page);
await fill(page, { code: '000000' }); await submit(page); await settle(page);
errorChecks.push(await errorSemantics(page, 'second-factor challenge, wrong code', 'code'));
await check('dev-clock', ENV, 'Dev profile: a staff member can complete the challenge with a code from a real authenticator (the current time)', async () => {
  const t = Date.now(); await fill(page, { code: totpAt(admin[2], Date.now() / 1000) }); await submit(page); await settle(page);
  assert.equal(P.posts(t)[0].status, 200, `the code for the real current time was answered ${P.posts(t)[0].status} ${P.posts(t)[0].body?.error?.code}; only a code computed for the pinned instant 2026-11-24T12:00Z is accepted`);
});
await fill(page, { code: totpAt(admin[2], PINNED) }); await submit(page); await waitHeading(page, 'My account'); await waitForm(page);
sweeps.push(await sweep(P, page, 'account (staff)', { allowSame: ['dev.owner@example.test', 'Dev'] }));
await page.goto(origin + '/account/recovery-codes'); await waitHeading(page, 'Generate new recovery codes'); await waitForm(page);
fieldChecks.push(await fields(page, 'recovery-code regeneration', { currentPassword: 'current-password', code: 'one-time-code' })); keys.push(await keyboard(page, 'recovery-code regeneration')); sweeps.push(await sweep(P, page, 'recovery-code regeneration')); await waitForm(page);
await page.goto(origin + '/account'); await waitHeading(page, 'My account'); await waitForm(page);

// Public pages under the policy with a session cookie present: a2.mjs.

// ---- 10.3 storage, 10.4 console, 10.1 URLs for this profile's whole journey
await page.goto(origin + '/account'); await waitHeading(page, 'My account');
const storage = await page.evaluate(async () => ({ local: Object.entries(localStorage), session: Object.entries(sessionStorage), idb: (await indexedDB.databases()).map(d => d.name), cookie: document.cookie, caches: await caches.keys().catch(() => []) }));
const urlHits = P.log.requests.flatMap(r => findSecrets(r.url + ' ' + (r.referer ?? ''))).concat(P.log.navigations.flatMap(findSecrets)); const consoleHits = P.log.console.flatMap(findSecrets);
fs.writeFileSync(path.join(EVIDENCE, 'a1-presentation.json'), JSON.stringify({ sweeps, keys, fieldChecks, errorChecks, storage, urlHits, consoleHits, console: P.log.console.slice(0, 60), pageErrors: P.log.pageErrors, violations: P.log.violations, external: P.log.external,
  referers: [...new Set(P.log.requests.map(r => r.referer))] }, null, 1));
await check('10.3', ENV, 'Browser storage after the donor and staff journeys: only the language and theme preferences; no IndexedDB, no cache storage, no script-readable cookie', async () => {
  assert.deepEqual(storage.local.map(e => e[0]).sort(), ['apv-locale', 'apv-theme']); assert.deepEqual(storage.session, []); assert.deepEqual(storage.idb, []); assert.equal(storage.cookie, ''); assert.deepEqual(storage.caches, []);
  assert.deepEqual(findSecrets(JSON.stringify(storage)), []);
});
await check('10.1', ENV, 'No password, code, secret or token in any request URL, navigation URL or Referer header of this journey', async () => { assert.deepEqual(urlHits, []); assert.ok(P.log.requests.every(r => !r.referer || !/[?#]/.test(r.referer))); return `${P.log.requests.length} requests inspected`; });
await check('10.4', ENV, 'Console output during the journey, failures included, holds no secret; no error or analytics reporting request exists', async () => { assert.deepEqual(consoleHits, []); assert.deepEqual(P.log.external, []); return `${P.log.console.length} console lines (expected failed-request notices only)`; });

const allSweep = sweeps.flatMap(s => s.problems), loads = sweeps.reduce((n, s) => n + s.loads, 0);
record('10.9', allSweep.some(p => /violation/.test(p)) || P.log.violations.length ? 'FAIL' : 'PASS', ENV, `Identity screens (${sweeps.map(s => s.label).join(', ')}) loaded from the production build with the policy enforced over HTTP, ${loads} loads: ${P.log.violations.length} policy violations, ${P.log.external.length} requests to another origin`);
const translation = allSweep.filter(p => /identical text|html lang/.test(p));
record('10.12', translation.length ? 'FAIL' : 'PASS', ENV, `Each of those screens in English and French: html lang follows, and no visible text is identical in both` + (translation.length ? ' — ' + translation.join('; ') : ''));
const layout = allSweep.filter(p => /overflow|clipped|contrast|theme is/.test(p));
record('10.13', layout.length ? 'FAIL' : 'PASS', ENV, `Each screen in light and dark at 360 and 1440 px: no horizontal overflow, nothing clipped, text contrast at least 4.5:1 (3:1 large); lowest measured ${Math.min(...sweeps.map(s => s.minContrast))}:1` + (layout.length ? ' — ' + layout.join('; ') : ''));
const kb = keys.flatMap(k => k.problems);
record('10.14', kb.length ? 'FAIL' : 'PASS', ENV, `Keyboard only on ${keys.map(k => k.label).join(', ')}: Tab reaches every control in document order with a visible focus indicator; Enter submits; focus moves to the field in error` + (kb.length ? ' — ' + kb.join('; ') : ''));
const er = errorChecks.flatMap(k => k.problems);
record('10.15', er.length ? 'FAIL' : 'PARTIAL', ENV, `Error semantics for ${errorChecks.map(k => k.label).join('; ')}: aria-invalid, aria-describedby to the message, text not colour, focus on the field` + (er.length ? ' — ' + er.join('; ') : ' — attributes verified; no screen reader was run'));
const fc = fieldChecks.flatMap(k => k.problems);
record('10.16', fc.length ? 'FAIL' : 'PARTIAL', ENV, `Autofill hints on ${fieldChecks.map(k => k.label).join(', ')}: username, current-password, new-password, one-time-code as appropriate; labels present; paste not blocked` + (fc.length ? ' — ' + fc.join('; ') : ' — attributes verified; no password manager was run'));
clearInterval(keepClear); await P.close(); await closeBrowser();
