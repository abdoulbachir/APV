// Env B, part 3: a second staff account (admin X) beside the super administrator (Y).
// Stale token on every POST (4.14/4.15), lost answers after real processing (3.17), cross-tab (4.13, 4.16a, 10.7).
import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto';
import { ROOT, EVIDENCE, assert, check, record, psql, resetThrottle, operator, freshTotp, profile, closeBrowser, secret, sleep, trail, staleTokenOnce, loseAnswerOnce, staffSignIn,
  waitHeading, waitForm, fill, submit, settle, pathOf, signIn, signOut, noticeText, heading } from './lib.mjs';

const ENV = 'B: production rules, HTTPS', DB = 'apv-m1b-accept-prod', origin = 'https://localhost:8443';
const Y = JSON.parse(fs.readFileSync(path.join(ROOT, 'env/state-b.json'), 'utf8'));
secret('password', Y.password); secret('authenticator secret', Y.secret);
const X = { email: `admin.${crypto.randomBytes(3).toString('hex')}@example.test`, name: 'Fictional Admin', password: secret('password', 'Kd9 thistle-marble comet 17') };
resetThrottle(DB);
const P = await profile(origin, { https: true }); const page = await P.open();
const inputsEmpty = async () => (await page.locator('.identity-panel input').evaluateAll(els => els.map(e => e.value))).every(v => v === '');
const startButton = () => page.locator('button.identity-primary', { hasText: 'Start authenticator setup' });
const staleResults = [];
/** One operation: a submission whose token is stale reaches the server once; then the same person submits again. */
async function stale(name, apiPath, meExpected, act, expectAgain) {
  const stop = await staleTokenOnce(P, apiPath); const t0 = Date.now();
  await act(); await settle(page); await sleep(1500); await stop();
  let ok = true, detail = '';
  try {
    const seen = trail(P, t0);
    assert.deepEqual(seen.slice(0, 3), [`POST ${apiPath.replace('/api', '')} 403`, 'GET /auth/csrf 200', `GET /me ${meExpected}`], seen.join(' , '));
    assert.equal(P.posts(t0)[0].body.error.code, 'csrf_invalid');
    assert.equal(P.sentPosts(t0).length, 1, 'POSTs sent: ' + P.sentPosts(t0).length);
    assert.match(await noticeText(page), /out of date\. Your request was not repeated/);
    assert.ok(await inputsEmpty(), 'a field kept its value');
  } catch (e) { ok = false; detail = String(e.message).split('\n')[0].slice(0, 300); }
  let again = true;
  const t1 = Date.now();
  try { await expectAgain(t1); } catch (e) { again = false; detail += ' | resubmission: ' + String(e.message).split('\n')[0].slice(0, 300); }
  staleResults.push({ name, ok, again, detail });
  console.log(`  stale ${name}: refusal ${ok ? 'ok' : 'FAILED'}, resubmission ${again ? 'ok' : 'FAILED'} ${detail}`);
}

// ---- lifecycle of X, every POST first sent with a stale token
const made = await operator('create-admin', [X.email, X.name]); assert.equal(made.code, 0, made.out); const credential = secret('one-time credential', made.credential);
await page.goto(origin + '/auth/set-password'); await waitHeading(page, 'Set or reset your password'); await waitForm(page);
await stale('set-password', '/api/auth/set-password', 401, async () => { await fill(page, { credential, newPassword: X.password }); await submit(page); },
  async t => { await waitForm(page); await fill(page, { credential, newPassword: X.password }); await submit(page); await settle(page); assert.equal(P.posts(t)[0].status, 204); assert.equal(pathOf(page), '/sign-in'); });
await waitForm(page);
await stale('sign-in', '/api/auth/login', 401, async () => { await fill(page, { email: X.email, password: X.password }); await submit(page); },
  async t => { await waitForm(page); await fill(page, { email: X.email, password: X.password }); await submit(page); await settle(page); assert.equal(P.posts(t)[0].body.status, 'mfa_enrollment_required'); assert.equal(pathOf(page), '/auth/enroll'); });
await waitForm(page);
await stale('enrollment start', '/api/auth/mfa/enroll', 401, async () => { await startButton().click(); },
  async t => { await waitForm(page); await startButton().click(); await page.locator('#code').waitFor(); assert.equal(P.posts(t)[0].status, 200); X.secret = P.posts(t)[0].body.secret; });
await stale('enrollment confirmation', '/api/auth/mfa/confirm', 401, async () => { await fill(page, { code: await freshTotp(X.secret) }); await submit(page); },
  async t => {
    // The screen was rebuilt: the secret that was displayed is gone with it, so setup starts again.
    await waitForm(page); assert.equal(await page.locator('svg.identity-qr').count(), 0);
    await startButton().click(); await page.locator('#code').waitFor(); X.secret = P.posts(t).find(r => r.path.endsWith('/enroll')).body.secret;
    await fill(page, { code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'Save your recovery codes');
    X.codes = await page.locator('ol.identity-codes code').allInnerTexts(); assert.equal(X.codes.length, 10);
    await page.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(page, 'My account');
  });
await waitForm(page);
await stale('sign-out', '/api/auth/logout', 200, async () => { await page.locator('button.identity-primary', { hasText: 'Sign out' }).click(); },
  async t => { await waitForm(page); assert.equal(pathOf(page), '/account'); await signOut(page); assert.equal(P.posts(t)[0].status, 204); assert.equal(pathOf(page), '/sign-in'); });
await signIn(page, X.email, X.password); await waitHeading(page, 'Verify your identity'); await waitForm(page);
await stale('second-factor verification', '/api/auth/mfa/verify', 401, async () => { await fill(page, { code: await freshTotp(X.secret) }); await submit(page); },
  async t => { await waitForm(page); assert.equal(pathOf(page), '/auth/mfa'); await fill(page, { code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'My account'); assert.equal(P.posts(t)[0].status, 200); });
await page.goto(origin + '/account/password'); await waitHeading(page, 'Change password'); await waitForm(page);
const x2 = secret('password', 'Lw3 juniper-slate lantern 82');
await stale('password change', '/api/auth/change-password', 200, async () => { await fill(page, { currentPassword: X.password, newPassword: x2 }); await submit(page); },
  async t => { await waitForm(page); assert.equal(pathOf(page), '/account/password'); await fill(page, { currentPassword: X.password, newPassword: x2 }); await submit(page); await settle(page); assert.equal(P.posts(t)[0].status, 204); X.password = x2; });
await page.goto(origin + '/account/recovery-codes'); await waitHeading(page, 'Generate new recovery codes'); await waitForm(page);
await stale('recovery-code regeneration', '/api/auth/mfa/recovery-codes', 200, async () => { await fill(page, { currentPassword: X.password, code: await freshTotp(X.secret) }); await submit(page); },
  async t => { await waitForm(page); await fill(page, { currentPassword: X.password, code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'Save your recovery codes'); X.codes = await page.locator('ol.identity-codes code').allInnerTexts(); assert.equal(P.posts(t)[0].status, 200); });
const done = staleResults.map(r => r.name).join(', ');
const bad = staleResults.filter(r => !r.ok), badAgain = staleResults.filter(r => !r.again);
record('4.14', bad.length ? 'FAIL' : 'PASS', ENV, `Stale token on each POST in turn (${done}): one request reaches the real server and is answered 403 csrf_invalid, then one GET /auth/csrf and one GET /me, the out-of-date message, fields cleared, no resend` + (bad.length ? ' — FAILED for: ' + bad.map(r => r.name + ' (' + r.detail + ')').join('; ') : ' — sign-up is covered in env A'));
record('4.15', badAgain.length ? 'FAIL' : 'PASS', ENV, `After each stale refusal the person submits again and the request succeeds with the new token (${done})` + (badAgain.length ? ' — FAILED for: ' + badAgain.map(r => r.name + ' (' + r.detail + ')').join('; ') : ''));

// ---- 3.17 lost answers. The server really processes the request; the answer never reaches the page.
resetThrottle(DB);
const lostResults = [];
async function lost(name, apiPath, opts, act, expect) {
  const l = await loseAnswerOnce(P, apiPath, opts); const t0 = Date.now(); await act(); await settle(page); await sleep(1500); await l.stop();
  let ok = true, detail = '';
  try { assert.ok(l.done, 'the request was never sent'); assert.equal(P.sentPosts(t0).filter(r => r.path === apiPath).length, 1, 'the POST was sent more than once'); await expect(l, t0); }
  catch (e) { ok = false; detail = String(e.message).split('\n')[0].slice(0, 300); }
  lostResults.push({ name, ok, detail, server: l.status, after: trail(P, t0).join(' , ') });
  console.log(`  lost ${name}: server ${l.status}; ${ok ? 'ok' : 'FAILED ' + detail}; then ${trail(P, t0).join(' , ')}`);
}
await page.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(page, 'My account'); await waitForm(page);
await lost('recovery-code regeneration', '/api/auth/mfa/recovery-codes', {}, async () => { await page.goto(origin + '/account/recovery-codes'); await waitForm(page); await fill(page, { currentPassword: X.password, code: await freshTotp(X.secret) }); await submit(page); },
  async l => { assert.equal(l.status, 200); assert.equal(await page.locator('ol.identity-codes').count(), 0); assert.match(await noticeText(page), /codes may have been created but could not be shown/); assert.equal(await page.evaluate(async () => (await fetch('/api/me', { cache: 'no-store' })).status), 200); });
const x3 = secret('password', 'Tn6 ember-willow sextant 39');
await lost('password change', '/api/auth/change-password', { keepCookie: false }, async () => { await page.goto(origin + '/account/password'); await waitForm(page); await fill(page, { currentPassword: X.password, newPassword: x3 }); await submit(page); },
  async l => { assert.equal(l.status, 204); X.password = x3; assert.equal(pathOf(page), '/sign-in'); assert.match(await noticeText(page), /could not be confirmed.*try your new password first/); });
await lost('sign-in (nothing of the answer arrived)', '/api/auth/login', { keepCookie: false }, async () => { await waitForm(page); await fill(page, { email: X.email, password: X.password }); await submit(page); },
  async l => { assert.equal(l.status, 200); assert.equal(pathOf(page), '/sign-in'); assert.match(await noticeText(page), /could not confirm the result\. Your request was not repeated/); assert.equal(await page.locator('#password').inputValue(), ''); });
await lost('sign-in (cookie arrived, body lost)', '/api/auth/login', { keepCookie: true }, async () => { await waitForm(page); await fill(page, { email: X.email, password: X.password }); await submit(page); },
  async l => { assert.equal(l.status, 200); await waitHeading(page, 'Verify your identity'); assert.equal(pathOf(page), '/auth/mfa'); });
await waitForm(page);
await lost('second-factor verification', '/api/auth/mfa/verify', { keepCookie: true }, async () => { await fill(page, { code: await freshTotp(X.secret) }); await submit(page); },
  async l => { assert.equal(l.status, 200); await waitHeading(page, 'My account'); assert.equal(pathOf(page), '/account'); });
await waitForm(page);
await lost('sign-out', '/api/auth/logout', { keepCookie: false }, async () => { await page.locator('button.identity-primary', { hasText: 'Sign out' }).click(); },
  async l => { assert.equal(l.status, 204); assert.equal(pathOf(page), '/sign-in'); assert.ok(!(await page.locator('body').innerText()).includes(X.email)); });
// recovery of X by the operator gives a fresh credential and an enrollment to lose
const rec = await operator('recover-staff-access', [X.email, 'Fictional test: confirmed by a phone call to the number on file']); assert.equal(rec.code, 0, rec.out); const recCredential = secret('one-time credential', rec.credential);
const x4 = secret('password', 'Bh5 cobalt-fern lighthouse 64');
await lost('set password', '/api/auth/set-password', {}, async () => { await page.goto(origin + '/auth/set-password'); await waitForm(page); await fill(page, { credential: recCredential, newPassword: x4 }); await submit(page); },
  async l => { assert.equal(l.status, 204); X.password = x4; assert.match(await noticeText(page), /result is uncertain\. Try signing in with your new password/); assert.ok(await inputsEmpty()); });
await page.goto(origin + '/sign-in'); await signIn(page, X.email, X.password); await waitHeading(page, 'Set up an authenticator'); await waitForm(page);
await lost('enrollment start', '/api/auth/mfa/enroll', {}, async () => { await startButton().click(); },
  async l => { assert.equal(l.status, 200); assert.equal(await page.locator('svg.identity-qr').count(), 0); await waitForm(page);
    const t = Date.now(); await startButton().click(); await page.locator('#code').waitFor(); X.secret = P.posts(t)[0].body.secret; assert.equal(P.posts(t)[0].status, 200); });
await lost('enrollment confirmation', '/api/auth/mfa/confirm', { keepCookie: true }, async () => { await fill(page, { code: await freshTotp(X.secret) }); await submit(page); },
  async l => { assert.equal(l.status, 200); assert.equal(await page.locator('ol.identity-codes').count(), 0); assert.match(await noticeText(page), /codes may have been created but could not be shown/);
    assert.equal(await page.evaluate(async () => (await (await fetch('/api/me', { cache: 'no-store' })).json()).mfa?.enrolled), true);
    await page.goto(origin + '/account/recovery-codes'); await waitHeading(page, 'Generate new recovery codes'); await waitForm(page);
    await fill(page, { currentPassword: X.password, code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'Save your recovery codes'); X.codes = await page.locator('ol.identity-codes code').allInnerTexts(); });
const lostBad = lostResults.filter(r => !r.ok);
record('3.17', lostBad.length ? 'FAIL' : 'PASS', ENV, `Answer dropped after real server processing, for each of: ${lostResults.map(r => r.name).join('; ')}. The frontend reached the right state through GET /me and never resent a password, code or credential` + (lostBad.length ? ' — FAILED for: ' + lostBad.map(r => r.name + ' (' + r.detail + ')').join('; ') : ' — sign-up is covered in env A'));
fs.writeFileSync(path.join(EVIDENCE, 'b3-stale-and-lost.json'), JSON.stringify({ staleResults, lostResults }, null, 1));
fs.writeFileSync(path.join(ROOT, 'env/state-x.json'), JSON.stringify(X), { mode: 0o600 });

// ---- cross-tab. "Tab B" without a page is the same browser profile (same cookie jar) making real requests; nothing announces the change to tab A.
resetThrottle(DB);
async function quiet(method, apiPath, body) {
  const headers = { Origin: origin };
  if (method === 'POST') { const t = await (await P.context.request.get(origin + '/api/auth/csrf')).json(); headers['X-CSRF-TOKEN'] = t.token; if (body) headers['Content-Type'] = 'application/json'; }
  const r = await P.context.request.fetch(origin + apiPath, { method, headers, ...(body ? { data: JSON.stringify(body) } : {}) });
  return { status: r.status(), body: await r.json().catch(() => null) };
}
await page.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(page, 'My account');
await page.goto(origin + '/account/password'); await waitHeading(page, 'Change password'); await waitForm(page);     // tab A shows X
const yChanged = psql(DB, `select changed_at from account_password p join account a on a.id=p.account_id where a.email_normalized='${Y.email}'`);
assert.equal((await quiet('POST', '/api/auth/logout')).status, 204);
assert.equal((await quiet('POST', '/api/auth/login', { email: Y.email, password: Y.password })).body.status, 'mfa_required');
assert.equal((await quiet('POST', '/api/auth/mfa/verify', { method: 'totp', code: await freshTotp(Y.secret) })).status, 200);       // the profile is now Y
let t0 = Date.now(); await fill(page, { currentPassword: X.password, newPassword: 'Xy1 unused-phrase never set 55' }); await submit(page); await settle(page); await sleep(1500);
await check('4.13', ENV, 'Tab A shows account X; the same browser signs out and signs in as Y without telling tab A; tab A submits a password change: refused csrf_invalid, token fetched, GET /me shows another identity, X\'s screen cleared, nothing resent, Y\'s password unchanged', async () => {
  assert.deepEqual(trail(P, t0).slice(0, 3), ['POST /auth/change-password 403', 'GET /auth/csrf 200', 'GET /me 200']); assert.equal(P.sentPosts(t0).length, 1);
  assert.equal(P.log.responses.filter(r => r.at >= t0 && r.path === '/api/me')[0].body.email, Y.email);
  const text = await page.locator('body').innerText(); assert.ok(!text.includes(X.email)); assert.ok(await inputsEmpty());
  assert.equal(psql(DB, `select changed_at from account_password p join account a on a.id=p.account_id where a.email_normalized='${Y.email}'`), yChanged);
  return 'tab A ended on ' + pathOf(page) + ' as Y with an empty form';
});

// 4.16a: A on X's challenge; the profile becomes pending as Y; A submits X's code
await quiet('POST', '/api/auth/logout'); await page.goto(origin + '/sign-in'); await signIn(page, X.email, X.password); await waitHeading(page, 'Verify your identity'); await waitForm(page);
assert.equal((await quiet('POST', '/api/auth/login', { email: Y.email, password: Y.password })).body.status, 'mfa_required');
t0 = Date.now(); await fill(page, { code: await freshTotp(X.secret) }); await submit(page); await settle(page); await sleep(1500);
await check('4.16a', ENV, 'Tab A on X\'s challenge; the same browser becomes pending as Y; tab A submits its code: one POST, refused csrf_invalid, GET /me answers mfa_required (indistinguishable), the challenge is shown again empty, nothing is resent', async () => {
  assert.deepEqual(trail(P, t0).slice(0, 3), ['POST /auth/mfa/verify 403', 'GET /auth/csrf 200', 'GET /me 401']); assert.equal(P.log.responses.filter(r => r.at >= t0 && r.path === '/api/me')[0].body.error.code, 'mfa_required');
  assert.equal(P.sentPosts(t0).length, 1); assert.equal(pathOf(page), '/auth/mfa'); assert.ok(await inputsEmpty()); assert.match(await noticeText(page), /out of date/);
  assert.equal(psql(DB, `select failures from pending_sign_in order by expires_at desc limit 1`), '0');
  return 'X\'s code was never checked against Y\'s pending sign-in (its failure count is still 0)';
});

// 10.7 with real tabs: the change is announced between tabs
await quiet('POST', '/api/auth/logout'); await page.goto(origin + '/sign-in'); await staffSignIn(page, X);
const tab2 = await P.open('/account'); await waitHeading(tab2, 'My account'); await waitForm(tab2);
await page.goto(origin + '/account/password'); await waitHeading(page, 'Change password'); await waitForm(page); await page.locator('#newPassword').fill('typed but never submitted 1');
await signOut(tab2);
await check('10.7', ENV, 'Another tab signs out: this tab leaves the private screen for the sign-in screen without being used, and what was typed is gone', async () => {
  await waitHeading(page, 'Sign in', 8000); assert.equal(pathOf(page), '/sign-in'); assert.ok(!(await page.locator('body').innerText()).includes(X.email)); assert.equal(await page.locator('#newPassword').count(), 0);
});
await staffSignIn(tab2, Y);
await check('10.7', ENV, 'Another tab signs in as another account: this tab shows that account\'s state without being used', async () => {
  await waitHeading(page, 'My account', 8000); assert.match(await page.locator('dl.identity-account').innerText(), new RegExp(Y.email.replace(/\./g, '\\.'))); assert.ok(!(await page.locator('body').innerText()).includes(X.email));
});
// Not covered here: the identical pending state announced between two real tabs. A first attempt at that
// journey did not complete (see docs/m1b-acceptance-evidence.md, finding 3, for the likely cause).
fs.writeFileSync(path.join(EVIDENCE, 'b3-observations.json'), JSON.stringify({ external: P.log.external, violations: P.log.violations, pageErrors: P.log.pageErrors, console: P.log.console.slice(0, 80) }, null, 1));
await P.close(); await closeBrowser();
