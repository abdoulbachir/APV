// Shared helpers for the M1B real-backend browser acceptance run.
// Real browser, real frontend build, real Spring Boot, real PostgreSQL. No identity response is mocked.
import { spawn, execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import jsQR from 'jsqr';
import { PNG } from 'pngjs';

export { assert };
export const ROOT = path.dirname(fileURLToPath(import.meta.url));
export const EVIDENCE = path.join(ROOT, 'evidence');
export const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---------------------------------------------------------------- results
const resultsFile = path.join(EVIDENCE, 'results.json');
export function record(id, status, env, note) {
  const all = fs.existsSync(resultsFile) ? JSON.parse(fs.readFileSync(resultsFile, 'utf8')) : {};
  (all[id] ??= []).push({ status, env, note, at: new Date().toISOString() });
  fs.writeFileSync(resultsFile, JSON.stringify(all, null, 1));
  console.log(`${status.padEnd(7)} ${id.padEnd(6)} [${env}] ${note}`);
}
/** Runs one scenario assertion block; a thrown assertion is a FAIL, never an abort of the run. */
export async function check(id, env, note, fn) {
  try { const extra = await fn(); record(id, 'PASS', env, note + (extra ? ' — ' + extra : '')); return true; }
  catch (e) { record(id, 'FAIL', env, note + ' — ' + String(e.message).split('\n')[0].slice(0, 400)); return false; }
}

// ---------------------------------------------------------------- environment
export function psql(container, sql) {
  return execFileSync('docker', ['exec', container, 'psql', '-U', 'apv', 'apv', '-Atc', sql], { encoding: 'utf8' }).trim();
}
export function resetThrottle(container) { psql(container, 'delete from auth_throttle'); }

/** Runs an operator command against the production-rule database, answering its questions on stdin. */
export function operator(command, answers) {
  return new Promise((resolve, reject) => {
    const child = spawn(path.join(ROOT, 'start-prod.sh'), ['operator', command], { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', d => out += d); child.stderr.on('data', d => out += d);
    child.on('error', reject);
    child.on('close', code => {
      const lines = out.split('\n');
      const at = lines.findIndex(l => /credential \(shown once/.test(l));
      const credential = at >= 0 ? lines.slice(at + 1).map(l => l.trim()).find(Boolean) : null;
      resolve({ code, out, credential, argv: child.spawnargs });
    });
    child.stdin.end(answers.join('\n') + '\n');
  });
}

// ---------------------------------------------------------------- TOTP (RFC 6238, SHA-1, 6 digits, 30 s)
function base32(text) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = '';
  for (const c of text.replace(/=+$/, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  return Buffer.from(bits.match(/.{8}/g).map(b => parseInt(b, 2)));
}
export function totpAt(secret, epochSeconds) {
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(epochSeconds / 30)));
  const mac = crypto.createHmac('sha1', base32(secret)).update(counter).digest();
  const o = mac[mac.length - 1] & 15;
  return String(((mac[o] & 127) << 24 | mac[o + 1] << 16 | mac[o + 2] << 8 | mac[o + 3]) % 1e6).padStart(6, '0');
}
const startStep = Math.floor(Date.now() / 30000); const lastSteps = new Map();
/** A code for a time step never used before for this secret (the server refuses a replayed step). */
export async function freshTotp(secret) {
  while (Math.floor(Date.now() / 30000) <= (lastSteps.get(secret) ?? startStep)) await sleep(500);
  lastSteps.set(secret, Math.floor(Date.now() / 30000));
  return totpAt(secret, Date.now() / 1000);
}
export function wrongTotp(secret) {
  const now = Date.now() / 1000; const near = [-30, 0, 30].map(d => totpAt(secret, now + d));
  let c = '000000'; while (near.includes(c)) c = String(Number(c) + 1).padStart(6, '0'); return c;
}

// ---------------------------------------------------------------- QR
export function decodeQrPng(buffer) {
  const png = PNG.sync.read(buffer);
  const found = jsQR(new Uint8ClampedArray(png.data), png.width, png.height);
  return found ? found.data : null;
}

// ---------------------------------------------------------------- browser
let browser;
export async function launch() { browser ??= await chromium.launch(); return browser; }
export async function closeBrowser() { await browser?.close(); }

/** Everything the run must never see in a URL, a referrer, the console, storage or a log. */
export const secrets = new Map();
export function secret(kind, value) { if (value && String(value).length >= 6) secrets.set(String(value), kind); return value; }
export function findSecrets(text) { const hits = []; for (const [v, k] of secrets) if (text.includes(v) || text.includes(encodeURIComponent(v))) hits.push(k); return hits; }

/**
 * A browser profile (its own cookie jar) with full observation: every request with its headers,
 * console output, content-policy violations, requests to other origins.
 */
export async function profile(origin, { locale = 'en', theme = 'light', width = 1280, https = false } = {}) {
  const b = await launch();
  const context = await b.newContext({ viewport: { width, height: 900 }, locale: locale === 'fr' ? 'fr-FR' : 'en-US', ignoreHTTPSErrors: https });
  const log = { requests: [], console: [], violations: [], external: [], pageErrors: [], navigations: [], responses: [] };
  await context.exposeFunction('apvAcceptViolation', v => { log.violations.push(v); });
  await context.addInitScript(({ locale, theme }) => {
    try { if (!localStorage.getItem('apv-locale')) localStorage.setItem('apv-locale', locale); if (!localStorage.getItem('apv-theme')) localStorage.setItem('apv-theme', theme); } catch { /* opaque origin */ }
    document.addEventListener('securitypolicyviolation', e => window.apvAcceptViolation(e.violatedDirective + ' ' + e.blockedURI));
  }, { locale, theme });
  context.on('request', r => {
    const url = r.url();
    log.requests.push({ method: r.method(), url, path: new URL(url).pathname, referer: r.headers().referer ?? null, at: Date.now() });
    if (!url.startsWith(origin + '/') && !url.startsWith('data:') && !url.startsWith('about:')) log.external.push(url);
  });
  context.on('response', async r => {
    const u = new URL(r.url());
    if (!u.pathname.startsWith('/api/')) return;
    const entry = { method: r.request().method(), path: u.pathname, status: r.status(), headers: r.headers(), at: Date.now() };
    log.responses.push(entry);
    try {
      const body = await r.json(); entry.body = body;
      if (u.pathname === '/api/auth/csrf') secret('csrf token', body.token);
      if (body?.secret) { secret('authenticator secret', body.secret); secret('otpauth uri', body.otpauthUri); }
      for (const c of body?.codes ?? []) secret('recovery code', c);
    } catch { /* no body */ }
  });
  context.on('console', m => log.console.push(m.type() + ': ' + m.text()));
  context.on('weberror', e => log.pageErrors.push(String(e.error())));
  const open = async (pathname = null) => {
    const page = await context.newPage();
    page.on('framenavigated', f => { if (f === page.mainFrame()) log.navigations.push(f.url()); });
    if (pathname) await page.goto(origin + pathname, { waitUntil: 'domcontentloaded' });
    return page;
  };
  const posts = (since = 0) => log.responses.filter(r => r.method === 'POST' && r.at >= since);
  const sentPosts = (since = 0) => log.requests.filter(r => r.method === 'POST' && r.at >= since);
  const cookie = async () => (await context.cookies()).find(c => /apv_session$/.test(c.name));
  return { context, log, open, posts, sentPosts, cookie, origin, close: () => context.close() };
}

// ---------------------------------------------------------------- page actions (what a person does)
export const heading = page => page.locator('.identity-panel h1').innerText();
export async function waitHeading(page, text, timeout = 15000) {
  await page.waitForFunction(t => document.querySelector('.identity-panel h1')?.textContent === t, text, { timeout });
}
export async function waitForm(page) { await page.locator('.identity-panel button.identity-primary:not([disabled])').first().waitFor({ state: 'visible', timeout: 15000 }); }
export async function fill(page, fields) { for (const [name, value] of Object.entries(fields)) await page.locator('#' + name).fill(value); }
export async function submit(page) { await page.locator('.identity-panel form button[type=submit]').click(); }
export async function settle(page) {
  // The form is busy while a request is in flight, and the screen is suspended while /me is re-read.
  await page.waitForFunction(() => !document.querySelector('.identity-panel fieldset[disabled]') && document.documentElement.dataset.identitySuspended !== 'true'
    && !/Checking|Preparing|Vérification|Préparation/.test(document.querySelector('.identity-panel [role=status]')?.textContent ?? ''), null, { timeout: 20000 });
  await page.waitForTimeout(250);
}
export const pathOf = page => new URL(page.url()).pathname;
export async function signIn(page, email, password) {
  await waitHeading(page, 'Sign in'); await waitForm(page);
  await fill(page, { email, password }); await submit(page); await settle(page);
}
export async function signOut(page) {
  await page.locator('button.identity-primary', { hasText: /^(Sign out|Se déconnecter)$/ }).click(); await settle(page);
}
export const fieldError = (page, name) => page.locator('#' + name + '-error').innerText({ timeout: 5000 });
export const alertText = async page => (await page.locator('.identity-panel [role=alert]').allInnerTexts()).join(' | ');
export const noticeText = async page => (await page.locator('.identity-notice').allInnerTexts()).join(' | ');

// ---------------------------------------------------------------- fault injection on real requests
/**
 * The next POST to this path reaches the real server carrying a genuine, well-formed token that is
 * not this session's: one the server issued to another session. Nothing is answered for the server.
 * (A token altered character by character is a different case: see the corrupted-token finding.)
 */
export async function staleTokenOnce(p, apiPath) {
  process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
  const foreign = (await (await fetch(p.origin + '/api/auth/csrf')).json()).token;
  let done = false;
  await p.context.route('**' + apiPath, async route => {
    if (done || route.request().method() !== 'POST') return route.fallback();
    done = true; await route.continue({ headers: { ...route.request().headers(), 'x-csrf-token': foreign } });
  });
  return () => p.context.unroute('**' + apiPath);
}
/**
 * The next POST to this path is really processed by the server and its answer never reaches the page.
 * keepCookie=false: nothing of the answer arrived, so the browser still holds the cookie it had.
 */
export async function loseAnswerOnce(p, apiPath, { keepCookie = false } = {}) {
  const lost = { done: false, status: null };
  await p.context.route('**' + apiPath, async route => {
    if (lost.done || route.request().method() !== 'POST') return route.fallback();
    lost.done = true; const before = await p.context.cookies();
    const answer = await route.fetch(); lost.status = answer.status();
    if (!keepCookie) { await p.context.clearCookies(); if (before.length) await p.context.addCookies(before); }
    await route.abort('connectionreset');
  });
  lost.stop = () => p.context.unroute('**' + apiPath);
  return lost;
}
/** What the page did after a POST: the API calls in order, as "METHOD path status". */
// GET /auth/options is the sign-in screen reading whether sign-up is open; it is not part of the reconciliation.
export function trail(p, since) { return p.log.responses.filter(r => r.at >= since && r.path !== '/api/auth/options').map(r => `${r.method} ${r.path.replace('/api', '')} ${r.status}`); }
/** Staff account made ready through the operator command and the real UI: activated, enrolled. */
export async function provisionStaff(p, page, command, email, name, password) {
  const made = await operator(command, [email, name]); assert.equal(made.code, 0, made.out.slice(-300)); secret('one-time credential', made.credential);
  await page.goto(p.origin + '/auth/set-password'); await waitHeading(page, 'Set or reset your password'); await waitForm(page);
  await fill(page, { credential: made.credential, newPassword: password }); await submit(page); await settle(page);
  await signIn(page, email, password); await waitHeading(page, 'Set up an authenticator'); await waitForm(page);
  const t = Date.now(); await page.locator('button.identity-primary', { hasText: 'Start authenticator setup' }).click(); await page.locator('#code').waitFor();
  const sec = p.log.responses.find(r => r.at >= t && r.path === '/api/auth/mfa/enroll').body.secret;
  await fill(page, { code: await freshTotp(sec) }); await submit(page); await waitHeading(page, 'Save your recovery codes');
  const codes = await page.locator('ol.identity-codes code').allInnerTexts();
  await page.locator('button.identity-primary', { hasText: 'I have saved my codes' }).click(); await waitHeading(page, 'My account'); await waitForm(page);
  return { email, name, password, secret: sec, codes };
}
/** Full staff sign-in through the UI. */
export async function staffSignIn(page, account) {
  await signIn(page, account.email, account.password); await waitHeading(page, 'Verify your identity'); await waitForm(page);
  await fill(page, { code: await freshTotp(account.secret) }); await submit(page); await waitHeading(page, 'My account'); await waitForm(page);
}
export const sessionIdOf = async p => Buffer.from((await p.cookie()).value, 'base64').toString();
