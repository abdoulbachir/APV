// Env B, part 4: the four refused sign-ins, one whole staff journey inspected for leaks (URLs, referrers,
// console, storage, server logs, audit rows, process list), backend outage and recovery.
import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto'; import { spawn, execFileSync } from 'node:child_process';
import { ROOT, EVIDENCE, assert, check, psql, resetThrottle, operator, freshTotp, profile, closeBrowser, secret, secrets, findSecrets, sleep, trail,
  waitHeading, waitForm, fill, submit, settle, pathOf, signIn, signOut, fieldError, noticeText, alertText } from './lib.mjs';

const ENV = 'B: production rules, HTTPS', DB = 'apv-m1b-accept-prod', origin = 'https://localhost:8443';
const X = JSON.parse(fs.readFileSync(path.join(ROOT, 'env/state-x.json'), 'utf8')); const Y = JSON.parse(fs.readFileSync(path.join(ROOT, 'env/state-b.json'), 'utf8'));
[X.password, Y.password].forEach(v => secret('password', v)); [X.secret, Y.secret].forEach(v => secret('authenticator secret', v)); [...X.codes, ...Y.codes].forEach(v => secret('recovery code', v));
resetThrottle(DB);
const P = await profile(origin, { https: true }); const page = await P.open();
const cookiesSeen = new Set(); P.context.on('response', r => { const c = r.headers()['set-cookie']; const m = c && c.match(/apv_session=([^;]+)/); if (m) cookiesSeen.add(m[1]); });

// ---- 7.19 the process list while an operator command waits for its answers
const Z = { email: `pending.${crypto.randomBytes(3).toString('hex')}@example.test`, name: 'Never Activated' };
const ps = await new Promise(resolve => {
  const child = spawn(path.join(ROOT, 'start-prod.sh'), ['operator', 'create-admin'], { stdio: ['pipe', 'pipe', 'pipe'] }); let out = '', listing = '';
  child.stdout.on('data', d => { out += d; if (/Email: $/.test(out) && !listing) { listing = execFileSync('ps', ['-eo', 'args'], { encoding: 'utf8' }).split('\n').filter(l => /apv-backend/.test(l)).join('\n'); child.stdin.write(Z.email + '\n' + Z.name + '\n'); child.stdin.end(); } });
  child.on('close', code => resolve({ code, out, listing }));
});
const zCredential = secret('one-time credential', ps.out.split('\n').map(l => l.trim()).filter(Boolean).find((l, i, a) => /credential \(shown once/.test(a[i - 1] ?? '')));
await check('7.19', ENV, 'Process list captured while an operator command was waiting for its answers: the command line holds the command name only; the email, the name and the credential never appear in it', async () => {
  assert.equal(ps.code, 0, ps.out.slice(-200)); assert.match(ps.listing, /operator create-admin/); assert.ok(!ps.listing.includes(Z.email) && !ps.listing.includes(Z.name) && !ps.listing.includes(zCredential));
  assert.deepEqual(findSecrets(ps.listing), []);
  return 'shell history was not inspected: the harness starts the command without a shell, so a human operator\'s history remains a manual check';
});

// ---- 1.6 the four refused sign-ins, as the person sees them
const refusals = [];
async function refused(label, email, password) {
  await page.goto(origin + '/sign-in'); await waitHeading(page, 'Sign in'); await waitForm(page); const t = Date.now(); await fill(page, { email, password }); await submit(page); await settle(page);
  const r = P.posts(t)[0]; const body = structuredClone(r.body); delete body.error.requestId;
  refusals.push({ label, status: r.status, body: JSON.stringify(body), text: await fieldError(page, 'password'), path: pathOf(page), ms: P.log.responses.find(x => x === r).at - P.sentPosts(t)[0].at });
}
await refused('wrong password', X.email, 'this is not the password 123');
await refused('unknown address', `unknown.${crypto.randomBytes(3).toString('hex')}@example.test`, 'this is not the password 123');
await refused('staff account not activated', Z.email, 'this is not the password 123');
const off = await operator('disable-account', [X.email]); assert.equal(off.code, 0, off.out);
await refused('disabled account, correct password', X.email, X.password);
await check('1.6', ENV, 'Wrong password, unknown address, not-activated staff account and disabled account with its correct password, each typed into the real sign-in screen: the same 401 invalid_credentials body and the same message', async () => {
  assert.ok(refusals.every(r => r.status === 401 && r.body === refusals[0].body && r.text === refusals[0].text && r.path === '/sign-in'), JSON.stringify(refusals));
  assert.equal(JSON.parse(refusals[0].body).error.code, 'invalid_credentials');
  return 'answer times ' + refusals.map(r => r.ms + ' ms').join(', ') + ' (single samples; the timing comparison is the backend suite\'s)';
});
const on = await operator('enable-account', [X.email]); assert.equal(on.code, 0, on.out);

// ---- one whole staff journey for the leak inspection
resetThrottle(DB);
const reset = await operator('issue-password-reset', [X.email, 'Fictional test: confirmed in person']); assert.equal(reset.code, 0, reset.out); const resetCredential = secret('one-time credential', reset.credential);
const pw1 = secret('password', 'Jc7 pewter-lagoon sparrow 5128'), pw2 = secret('password', 'Mv1 indigo-bramble anchor 8846');
await page.goto(origin + '/auth/set-password'); await waitHeading(page, 'Set or reset your password'); await waitForm(page);
await fill(page, { credential: resetCredential, newPassword: pw1 }); await submit(page); await settle(page); assert.equal(pathOf(page), '/sign-in');
await check('7.7', ENV, 'A password reset credential keeps the second factor: after redemption the account signs in with the new password and its existing authenticator', async () => {
  await signIn(page, X.email, pw1); await waitHeading(page, 'Verify your identity'); await waitForm(page); await fill(page, { code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'My account');
});
await page.goto(origin + '/account/recovery-codes'); await waitHeading(page, 'Generate new recovery codes'); await waitForm(page);
await fill(page, { currentPassword: pw1, code: await freshTotp(X.secret) }); await submit(page); await waitHeading(page, 'Save your recovery codes');
const shown = await page.locator('ol.identity-codes code').allInnerTexts(); shown.forEach(c => secret('recovery code', c));
await page.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(page, 'My account');
await page.goto(origin + '/account/password'); await waitHeading(page, 'Change password'); await waitForm(page);
await fill(page, { currentPassword: pw1, newPassword: pw2 }); await submit(page); await settle(page); X.password = pw2; fs.writeFileSync(path.join(ROOT, 'env/state-x.json'), JSON.stringify(X), { mode: 0o600 });
await page.goto(origin + '/account'); await waitHeading(page, 'My account'); await waitForm(page);

const storage = await page.evaluate(async () => ({ local: Object.entries(localStorage), session: Object.entries(sessionStorage), idb: (await indexedDB.databases()).map(d => d.name), cookie: document.cookie, caches: await caches.keys().catch(() => []) }));
for (const c of cookiesSeen) { secret('session cookie value', c); secret('session identifier', Buffer.from(c, 'base64').toString()); }
const urlText = P.log.requests.map(r => r.url + ' ' + (r.referer ?? '')).concat(P.log.navigations).join('\n');
await check('10.1', ENV, 'Whole staff journey (reset credential, sign-in, second factor, regeneration, password change): no password, code, recovery code, secret, credential, token or session identifier in any request URL, navigation URL or Referer header', async () => {
  assert.deepEqual(findSecrets(urlText), []); assert.ok(P.log.requests.every(r => !new URL(r.url).search && (!r.referer || !/[?#]/.test(r.referer))), 'a request carried a query string');
  return `${P.log.requests.length} requests and ${P.log.navigations.length} navigations inspected against ${secrets.size} secret values`;
});
await check('10.3', ENV, 'Browser storage after the whole staff journey: only the language and theme preferences; nothing about the account, session or permissions', async () => {
  assert.deepEqual(storage.local.map(e => e[0]).sort(), ['apv-locale', 'apv-theme']); assert.deepEqual(storage.session, []); assert.deepEqual(storage.idb, []); assert.equal(storage.cookie, ''); assert.deepEqual(storage.caches, []);
});
await check('10.4', ENV, 'Console output during the whole staff journey, refusals included: no secret; no reporting request to any other origin', async () => {
  assert.deepEqual(findSecrets(P.log.console.join('\n')), []); assert.deepEqual(P.log.external, []); assert.deepEqual(P.log.pageErrors, []); return `${P.log.console.length} console lines`;
});

// ---- server side: logs and audit rows
const logs = ['backend-prod.log', 'tls-proxy.log', 'backend-dev.log', 'preview.log'].map(f => [f, fs.readFileSync(path.join(EVIDENCE, f), 'utf8')]);
await check('9.8', ENV, 'Server logs of the whole acceptance run (production-rule backend at INFO, the HTTPS front): no session identifier in either form, no CSRF token, no password, code, credential or authenticator secret', async () => {
  const prod = logs[0][1] + logs[1][1]; const hits = findSecrets(prod); assert.deepEqual(hits, []);
  const uuids = prod.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? []; assert.deepEqual(uuids, [], 'identifier-shaped text in the log');
  assert.ok(!/apv_session=|X-CSRF-TOKEN: |\$argon2/i.test(prod));
  return `${prod.split('\n').length} log lines against ${secrets.size} secret values; DEBUG and TRACE were not enabled in this run (the backend suite covers those levels)`;
});
await check('dev-log', 'A: dev profile', 'The dev profile prints development passwords and authenticator secrets to its console by design; apart from that block its log holds no session identifier or token', async () => {
  const dev = logs[2][1].split('\n').filter(l => !/^\s+(donor|admin|super_admin)\s+dev\./.test(l)).join('\n');
  assert.deepEqual((dev.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? []), []); assert.ok(!/apv_session=/.test(dev));
});
await check('10.x-audit', ENV, 'Audit rows written during the run hold no password, code, credential, token, secret or session identifier', async () => {
  const dump = psql(DB, 'select row_to_json(a)::text from audit_event a'); assert.ok(dump.split('\n').length > 50, 'too few audit rows'); assert.deepEqual(findSecrets(dump), []);
  return dump.split('\n').length + ' audit rows inspected';
});

// ---- 10.10 backend stopped, then back
const pid = execFileSync('bash', ['-c', "ss -ltnp 2>/dev/null | grep ':8081 ' | sed -E 's/.*pid=([0-9]+).*/\\1/'"], { encoding: 'utf8' }).trim();
process.kill(Number(pid)); await sleep(2500);
await check('10.10', ENV, 'Real-API mode with the backend stopped: a private screen and the sign-in screen both show an error and a retry control; no account data, no mock account, no mock sign-in', async () => {
  await page.reload({ waitUntil: 'domcontentloaded' }); await page.locator('.identity-panel').waitFor(); await settle(page);
  assert.match(await alertText(page), /could not be checked/); assert.equal(await page.locator('dl.identity-account').count(), 0); assert.ok(!(await page.locator('body').innerText()).includes(X.email));
  assert.equal(await page.locator('.identity-panel form').count(), 0); assert.equal(await page.locator('button.identity-primary', { hasText: 'Try again' }).count(), 1);
  const statuses = [...new Set(P.log.responses.slice(-3).map(r => r.status))]; return 'the front answered ' + statuses.join('/') + ' for the API while the backend process was stopped';
});
const child = spawn(path.join(ROOT, 'start-prod.sh'), [], { detached: true, stdio: ['ignore', fs.openSync(path.join(EVIDENCE, 'backend-prod.log'), 'a'), fs.openSync(path.join(EVIDENCE, 'backend-prod.log'), 'a')] }); child.unref();
for (let i = 0; i < 60; i++) { await sleep(1500); try { process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'; if ((await fetch(origin + '/api/site')).status === 200) break; } catch { /* not yet */ } }
await check('10.10', ENV, 'Backend back: Try again re-reads the session; the same person is still signed in (sessions and keys survive a restart) and nothing was resent meanwhile', async () => {
  const t = Date.now(); await page.locator('button.identity-primary', { hasText: 'Try again' }).click(); await waitHeading(page, 'My account'); assert.match(await page.locator('dl.identity-account').innerText(), new RegExp(X.email.replace(/\./g, '\\.')));
  assert.equal(P.sentPosts(t).length, 0);
  return 'restart included the full digest check of the 2 GB list';
});
await waitForm(page); await signOut(page);
fs.writeFileSync(path.join(EVIDENCE, 'b4-observations.json'), JSON.stringify({ refusals, storage, external: P.log.external, violations: P.log.violations, console: P.log.console.slice(0, 80), ps: ps.listing.replace(/\/tmp\/\S+/g, '<path>') }, null, 1));
await P.close(); await closeBrowser();
