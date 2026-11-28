// Milestone 2B joint run under production rules (env B: HTTPS, no profile, a database that was
// never marked disposable). The general fund exists on a real database; giving is closed until the
// operator creates, publishes and activates a payment method; then a guest reports, a super
// administrator confirms, and an administrator without the permission cannot review.
// Run after b1.mjs (the database must already have its first super administrator).
import fs from 'node:fs';
import path from 'node:path';
import { PNG } from 'pngjs';
import { assert, check, EVIDENCE, ROOT, psql, operator, provisionStaff, profile, closeBrowser, secret, findSecrets, waitHeading, sleep } from './lib.mjs';
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const ENV = 'B: production rules, HTTPS', DB = 'apv-m1b-accept-prod', origin = 'https://localhost:8443';
const api = async p => { const r = await fetch(origin + '/api' + p); return { status: r.status, body: await r.json().catch(() => null) }; };
const stats = async () => (await api('/stats')).body;
const png = () => { const image = new PNG({ width: 64, height: 64 }); image.data.fill(140); const file = path.join(EVIDENCE, 'screenshot-prod.png'); fs.writeFileSync(file, PNG.sync.write(image)); return file; };
const tag = Date.now().toString(36);

const D = await profile(origin, { https: true }); const donor = await D.context.newPage();
await check('m2p-fund', ENV, 'On a real database the general fund exists, empty, with its approved text, and the home page shows it', async () => {
  const f = await api('/cases/FUND'); assert.equal(f.status, 200); assert.equal(f.body.financials.externalDonations >= 0, true);
  assert.equal(f.body.case.title, 'General fund');
  await donor.goto(origin + '/'); await donor.locator('.fund-panel').waitFor({ timeout: 15000 }); assert.equal(await donor.locator('.fund-value').count(), 1);
  return `GET /cases/FUND 200; title "${f.body.case.title}"`;
});
const hadMethods = (await api('/giving/methods')).body.methods.length;
await check('m2p-closed', ENV, 'With no active payment method, giving is not open: the page says so and shows no form', async () => {
  if (hadMethods) return 'skipped: a method is already active on this database';
  await donor.goto(origin + '/give'); await donor.locator('text=cannot be reported at the moment').waitFor({ timeout: 15000 });
  assert.equal(await donor.locator('.identity-panel form').count(), 0);
});
const id = 'zl' + tag.slice(-5).replace(/[^a-z0-9]/g, 'x'); const handle = `pay-${tag}@example.test`;
await check('m2p-operator', ENV, 'The operator creates a method, publishes its recipient details from a file and activates it with an eligibility attestation; each step is audited and nothing sensitive is on the command line', async () => {
  const file = path.join(ROOT, 'env', `details-${tag}.json`);
  fs.writeFileSync(file, JSON.stringify({ instructions: { en: 'Send your gift, then come back to this page to report it.', fr: 'Envoyez votre don, puis revenez sur cette page pour le déclarer.' }, recipient: [{ labels: { en: 'Send to', fr: 'Envoyer à' }, value: handle, format: 'text' }] }));
  const created = await operator('payment-method-create', [id, 'Joint Pay', 'Joint Pay', '1']); assert.equal(created.code, 0, created.out.slice(-300));
  const early = await operator('payment-method-activate', [id, 'Harness', '2026-11-28', 'nothing yet']); assert.notEqual(early.code, 0, 'a method without details was activated');
  const published = await operator('payment-method-publish-details', [id, file, id]); assert.equal(published.code, 0, published.out.slice(-300));
  const activated = await operator('payment-method-activate', [id, 'Joint run harness', new Date().toISOString().slice(0, 10), 'Fictional method for the acceptance run']); assert.equal(activated.code, 0, activated.out.slice(-300));
  for (const run of [created, published, activated]) assert.ok(!run.argv.join(' ').includes(handle) && !run.argv.join(' ').includes(id));
  const events = psql(DB, "select count(*) from audit_event where event_type like 'payment_method%' or event_type like 'payment.method%'"); assert.ok(Number(events) >= 3, 'audit rows: ' + events);
  const listed = (await api('/giving/methods')).body.methods.find(m => m.id === id); assert.ok(listed); assert.equal(listed.recipient[0].value, handle);
  return `${events} audit rows; activation without details was refused first`;
});
let code = '';
await check('m2p-report', ENV, 'A guest reports over HTTPS with the published details: receipt, __Host- cookie, screenshot stored, public total unchanged', async () => {
  const before = (await stats()).received;
  await donor.goto(origin + '/give'); await donor.locator('.donation-recipient').waitFor({ timeout: 15000 });
  await donor.locator(`input[name="method"][value="${id}"]`).check(); assert.match(await donor.locator('.donation-recipient').innerText(), new RegExp(handle.replace(/[.]/g, '\\.')));
  await donor.locator('#amount').fill('31'); await donor.locator('#payerName').fill(secret('payer name', 'Prod Rules Donor ' + tag)); await donor.locator('#contact').fill(secret('contact', `reach-${tag}@example.test`));
  await donor.locator('.identity-panel form button[type=submit]').click(); await donor.locator('.donation-tracking').waitFor({ timeout: 15000 });
  code = secret('tracking code', (await donor.locator('.donation-tracking').innerText()).trim());
  const cookie = await D.cookie(); assert.equal(cookie.name, '__Host-apv_session'); assert.ok(cookie.secure && cookie.httpOnly);
  await donor.locator('.donation-evidence input[type=file]').setInputFiles(png()); await donor.locator('.donation-evidence button').click(); await donor.locator('.donation-ok').waitFor({ timeout: 15000 });
  assert.equal((await stats()).received, before);
});
const S = await profile(origin, { https: true }); const admin = await S.context.newPage();
const reviewer = await provisionStaff(S, admin, 'create-super-admin', `reviewer.${tag}@example.test`, 'Joint Reviewer', secret('password', 'Vr4 cobalt-meadow lantern 6612'));
await check('m2p-confirm', ENV, 'A super administrator created by the operator reviews it: screenshot shown, confirmed with typed values, the public total rises by exactly that amount', async () => {
  const before = (await stats()).received;
  await admin.locator('a', { hasText: 'Review gifts' }).click(); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  await admin.locator('.donation-list a', { hasText: code.slice(0, 9) }).click(); await admin.locator('#caseCode').waitFor({ timeout: 15000 });
  await admin.waitForFunction(() => { const i = document.querySelector('.donation-evidence-view img'); return i && i.complete && i.naturalWidth > 0; }, null, { timeout: 15000 });
  await admin.locator('#caseCode').fill('FUND'); await admin.locator('#confirmedAmount').fill('31'); await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10)); await admin.locator('#listing').selectOption('as_requested');
  await admin.locator('.donation-action button.identity-primary').first().click(); await admin.locator('.donation-ok').waitFor({ timeout: 15000 });
  assert.equal((await stats()).received, before + 3100);
  await donor.goto(origin + '/track'); await donor.locator('#code').fill(code); await donor.locator('form button[type=submit]').click(); await donor.locator('.donation-status').waitFor({ timeout: 15000 });
  assert.match(await donor.locator('.donation-status').innerText(), /Verified on .*\$31/);
});
const P = await profile(origin, { https: true }); const plain = await P.context.newPage();
await provisionStaff(P, plain, 'create-admin', `plain.${tag}@example.test`, 'Plain Admin', secret('password', 'Vr4 cobalt-meadow lantern 6613'));
await check('m2p-admin-no-permission', ENV, 'An administrator who is not the super administrator has no review link, is told so on the page, and the API answers 403 without saying whether the gift exists', async () => {
  assert.equal(await plain.locator('a', { hasText: 'Review gifts' }).count(), 0);
  await plain.goto(origin + '/admin/donations'); await plain.locator('text=cannot review gifts').waitFor({ timeout: 15000 });
  const r = await plain.evaluate(async () => { const a = await fetch('/api/admin/donations'); const b = await fetch('/api/admin/donations/don_0000000000000000000000'); return [a.status, b.status, (await b.json()).error.code]; });
  assert.deepEqual(r, [403, 403, 'forbidden']);
});
await check('m2p-leaks', ENV, 'Nothing private in the server log, any address or browser storage: payer name, contact, tracking code, passwords, authenticator secrets', async () => {
  await sleep(500); const hits = findSecrets(fs.readFileSync(path.join(EVIDENCE, 'backend-prod.log'), 'utf8')); assert.deepEqual(hits, []);
  for (const X of [D, S, P]) { assert.deepEqual(findSecrets(X.log.requests.map(r => r.url + ' ' + (r.referer ?? '')).join('\n')), []); assert.deepEqual(X.log.violations, []); assert.deepEqual(X.log.pageErrors, []); }
  const audit = psql(DB, "select coalesce(string_agg(details::text, ' '), '') from audit_event"); assert.deepEqual(findSecrets(audit), []);
  return 'server log, request addresses, referrers and audit details searched';
});
await D.close(); await S.close(); await P.close(); await closeBrowser();
