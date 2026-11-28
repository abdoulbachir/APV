// Milestone 2B joint run, second part (env A): presentation, keyboard, lost answers, a stale token,
// a card-shaped value, a donor account at the review pages, and a staff session that ends mid-review.
// Run after m2.mjs, against the same servers.
import fs from 'node:fs';
import path from 'node:path';
import { assert, check, EVIDENCE, psql, profile, closeBrowser, secret, waitHeading, waitForm, signIn, staffSignIn, staleTokenOnce, loseAnswerOnce, sleep } from './lib.mjs';
import { sweep, keyboard } from './ui-checks.mjs';
const ENV = 'A: dev profile, HTTP preview', origin = 'http://127.0.0.1:4173', DB = 'apv-m2-accept-dev';
const log = fs.readFileSync(path.join(EVIDENCE, 'backend-dev.log'), 'utf8');
const o = log.match(/super_admin\s+(\S+)\s+password:\s+(\S+)\s+authenticator secret:\s+(\S+)/), d = log.match(/donor\s+(\S+)\s+password:\s+(\S+)/);
const staff = { email: o[1], password: secret('password', o[2]), secret: secret('authenticator secret', o[3]) };
const stats = async () => (await (await fetch('http://127.0.0.1:8080/api/stats')).json());
const gifts = name => Number(psql(DB, `select count(*) from donation where payer_name = '${name}'`));
const posts = (P, t, p = '/api/donations') => P.log.responses.filter(r => r.at >= t && r.method === 'POST' && r.path === p).map(r => r.status);
const sent = (P, t, p = '/api/donations') => P.log.requests.filter(r => r.at >= t && r.method === 'POST' && new URL(r.url).pathname === p).length;
psql(DB, 'delete from donation_throttle'); psql(DB, 'delete from auth_throttle');

const D = await profile(origin); const page = await D.context.newPage();
const open = async () => { await page.goto(origin + '/give'); await page.locator('.donation-recipient').waitFor({ timeout: 15000 }); };
const fillIn = async (amount, name) => { await page.locator('#amount').fill(String(amount)); await page.locator('#payerName').fill(name); };
const submit = () => page.locator('.identity-panel form button[type=submit]').click();

await open();
await check('m2-present-give', ENV, 'Report form in English and French, light and dark, 360 and 1440 px: language, theme, no overflow, no clipped control, contrast, no untranslated text, no policy violation', async () => {
  const r = await sweep(D, page, 'give', { allowSame: ['Zelle', 'PayPal', 'Cash App', 'Apple Cash'] }); assert.deepEqual(r.problems, []); return `8 combinations, lowest contrast ${r.minContrast}`;
});
await check('m2-keyboard-give', ENV, 'Report form with the keyboard alone: Tab reaches every control in order with a visible focus', async () => {
  await page.locator('.donation-recipient').waitFor(); const r = await keyboard(page, 'give'); assert.deepEqual(r.problems, []); return `${r.controls} controls`;
});
await page.goto(origin + '/track'); await page.locator('#code').waitFor();
await check('m2-present-track', ENV, 'Status page in the eight combinations', async () => { const r = await sweep(D, page, 'track'); assert.deepEqual(r.problems, []); return `lowest contrast ${r.minContrast}`; });

await check('m2-lost-answer', ENV, 'The answer to a report is lost: the page says so and resends nothing; the person submits again and gets the receipt of the one gift that exists', async () => {
  await open(); await fillIn(21, 'Lost Answer Donor'); const before = (await stats()).received; const lost = await loseAnswerOnce(D, '/api/donations', { keepCookie: true });
  let t = Date.now(); await submit(); await page.locator('.identity-panel [role=alert]').waitFor({ timeout: 15000 }); await lost.stop();
  assert.equal(lost.status, 201); await sleep(1500); assert.equal(sent(D, t), 1); assert.equal(gifts('Lost Answer Donor'), 1);
  t = Date.now(); await submit(); await page.locator('.donation-tracking').waitFor({ timeout: 15000 });
  assert.deepEqual(posts(D, t), [201]); assert.equal(gifts('Lost Answer Donor'), 1); assert.equal((await stats()).received, before);
  return 'first POST processed (201) and lost; the same key and body returned the receipt; one row';
});
await check('m2-stale-token', ENV, 'A report sent with another session\'s token: 403 csrf_invalid, a message, one request, nothing recorded; the entries are kept and a second submit succeeds', async () => {
  await open(); await fillIn(22, 'Stale Token Donor'); const stop = await staleTokenOnce(D, '/api/donations');
  let t = Date.now(); await submit(); await page.locator('.identity-panel [role=alert]').waitFor({ timeout: 15000 }); await stop();
  assert.deepEqual(posts(D, t), [403]); await sleep(1500); assert.equal(sent(D, t), 1); assert.equal(gifts('Stale Token Donor'), 0);
  assert.match(await page.locator('.identity-panel [role=alert]').innerText(), /out of date/); assert.equal(await page.locator('#payerName').inputValue(), 'Stale Token Donor');
  t = Date.now(); await submit(); await page.locator('.donation-tracking').waitFor({ timeout: 15000 }); assert.deepEqual(posts(D, t), [201]); assert.equal(gifts('Stale Token Donor'), 1);
});
await check('m2-double-submit', ENV, 'Pressing the button twice sends one report', async () => {
  await open(); await fillIn(23, 'Double Press Donor'); const t = Date.now(); const button = page.locator('.identity-panel form button[type=submit]');
  await Promise.all([button.click(), button.click({ force: true }).catch(() => {})]); await page.locator('.donation-tracking').waitFor({ timeout: 15000 });
  assert.equal(sent(D, t), 1); assert.equal(gifts('Double Press Donor'), 1);
});
await check('m2-card-number', ENV, 'A card-shaped value in a text field: refused, the field is named with the reason, nothing stored', async () => {
  await open(); await fillIn(24, '4111 1111 1111 1111'); const t = Date.now(); await submit(); await page.locator('.identity-panel [role=alert]').waitFor({ timeout: 15000 });
  assert.deepEqual(posts(D, t), [400]); assert.match(await page.locator('.donation-problems').innerText(), /card number/); assert.equal(Number(psql(DB, "select count(*) from donation where payer_name like '4111%'")), 0);
  assert.ok(!fs.readFileSync(path.join(EVIDENCE, 'backend-dev.log'), 'utf8').includes('4111 1111 1111 1111'), 'the value is in the backend log');
});
await check('m2-french-phone', ENV, 'In French at 390 px: the form, the receipt and the status are in French with no sideways scroll', async () => {
  await page.setViewportSize({ width: 390, height: 800 }); await page.evaluate(() => localStorage.setItem('apv-locale', 'fr')); await open();
  assert.equal(await page.evaluate(() => document.documentElement.lang), 'fr'); assert.match(await page.locator('.identity-panel h1').innerText(), /Déclarer un don/);
  await fillIn(25, 'Donateur Mobile'); await submit(); await page.locator('.donation-tracking').waitFor({ timeout: 15000 });
  assert.match(await page.locator('.identity-panel h1').innerText(), /Votre don est enregistré/); const code = await page.locator('.donation-tracking').innerText();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  await page.goto(origin + '/track'); await page.locator('#code').fill(code); await page.locator('form button[type=submit]').click(); await page.locator('.donation-status').waitFor({ timeout: 15000 });
  assert.match(await page.locator('.donation-status').innerText(), /En attente de vérification/); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  await page.evaluate(() => localStorage.setItem('apv-locale', 'en')); await page.setViewportSize({ width: 1280, height: 900 });
});

const N = await profile(origin); const dn = await N.context.newPage();
await check('m2-donor-account', ENV, 'A signed-in donor account at the review pages: told it cannot review; the API answers 403 forbidden', async () => {
  await dn.goto(origin + '/sign-in'); await signIn(dn, d[1], secret('password', d[2])); await waitHeading(dn, 'My account');
  assert.equal(await dn.locator('a', { hasText: 'Review gifts' }).count(), 0);
  await dn.goto(origin + '/admin/donations'); await dn.locator('text=cannot review gifts').waitFor({ timeout: 15000 });
  const r = await dn.evaluate(async () => { const x = await fetch('/api/admin/donations'); return [x.status, (await x.json()).error.code]; }); assert.deepEqual(r, [403, 'forbidden']);
});

const S = await profile(origin); const admin = await S.context.newPage();
await admin.goto(origin + '/sign-in'); await staffSignIn(admin, staff);
await check('m2-admin-french-phone', ENV, 'Review queue and a gift page in French at 390 px: translated, no sideways scroll', async () => {
  await admin.setViewportSize({ width: 390, height: 800 }); await admin.evaluate(() => localStorage.setItem('apv-locale', 'fr'));
  await admin.goto(origin + '/admin/donations'); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  assert.match(await admin.locator('.donation-admin-panel h1').innerText(), /Dons à vérifier/); assert.equal(await admin.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  await admin.locator('.donation-list a').first().click(); await admin.locator('.donation-facts').first().waitFor({ timeout: 15000 });
  assert.equal(await admin.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false); assert.match(await admin.locator('.donation-admin-panel').innerText(), /Ce qui a été déclaré/);
  await admin.evaluate(() => localStorage.setItem('apv-locale', 'en')); await admin.setViewportSize({ width: 1280, height: 900 });
});
await check('m2-provider-reference', ENV, 'One payment is counted once: a report confirmed with a provider reference, then a manual entry with the same reference and method is refused and nothing is added', async () => {
  const method = (await (await fetch('http://127.0.0.1:8080/api/giving/methods')).json()).methods[0].id; const ref = 'JOINT-REF-' + Date.now();
  await admin.goto(origin + '/admin/donations'); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  await admin.locator('.donation-list a', { hasText: 'Double Press Donor' }).click(); await admin.locator('#caseCode').waitFor({ timeout: 15000 });
  await admin.locator('#caseCode').fill('FUND'); await admin.locator('#confirmedAmount').fill('23'); await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10));
  await admin.locator('#providerReference').fill(ref); await admin.locator('#listing').selectOption('hidden');
  await admin.locator('.donation-action button.identity-primary').first().click(); await admin.locator('.donation-ok').waitFor({ timeout: 15000 });
  const total = (await stats()).received;
  await admin.goto(origin + '/admin/donations/new'); await admin.locator('#caseCode').waitFor({ timeout: 15000 });
  await admin.locator('#caseCode').fill('FUND'); await admin.locator('#amount').fill('23'); await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10));
  await admin.locator('#methodId').fill(method); await admin.locator('#payerName').fill('Same Payment Again'); await admin.locator('#providerReference').fill(ref); await admin.locator('#note').fill('Joint run: same payment entered by hand');
  await admin.locator('.donation-action button.identity-primary').click(); await admin.locator('.donation-action [role=alert]').waitFor({ timeout: 15000 });
  assert.match(await admin.locator('.donation-action [role=alert]').innerText(), /already recorded/); assert.equal((await stats()).received, total); assert.equal(gifts('Same Payment Again'), 0);
  return `method ${method}; 409 duplicate_payment_reference`;
});
await check('m2-admin-session-lost', ENV, 'The staff session ends while a confirmation is being typed: the POST is refused 403, is not sent again, the sign-in screen follows, and the gift is still waiting', async () => {
  await admin.goto(origin + '/admin/donations'); await admin.locator('.donation-list').waitFor({ timeout: 15000 });
  await admin.locator('.donation-list a', { hasText: 'Stale Token Donor' }).click(); await admin.locator('#caseCode').waitFor({ timeout: 15000 });
  const id = decodeURIComponent(new URL(admin.url()).pathname.split('/').pop()); const total = (await stats()).received;
  await admin.locator('#caseCode').fill('FUND'); await admin.locator('#confirmedAmount').fill('22'); await admin.locator('#receivedOn').fill(new Date().toISOString().slice(0, 10)); await admin.locator('#listing').selectOption('as_requested');
  psql(DB, "delete from spring_session where principal_name is not null"); const t = Date.now(); const p = `/api/admin/donations/${id}/confirm`;
  await admin.locator('.donation-action button.identity-primary').first().click(); await waitHeading(admin, 'Sign in', 20000);
  assert.deepEqual(posts(S, t, p), [403]); await sleep(1500); assert.equal(sent(S, t, p), 1);
  assert.equal(psql(DB, `select state from donation where public_id = '${id}'`), 'reported'); assert.equal((await stats()).received, total);
});
await check('m2-clean-2', ENV, 'No content-policy violation or page error in any browser during this part', async () => {
  for (const P of [D, N, S]) { assert.deepEqual(P.log.violations, []); assert.deepEqual(P.log.pageErrors, []); }
});
await D.close(); await N.close(); await S.close(); await closeBrowser();
