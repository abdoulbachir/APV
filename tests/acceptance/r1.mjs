// R1: a sign-out sent just before a password change of the same session answers. After a sign-out
// answered 204, the cookie the password change issued must not be a signed-in session.
// Statistical: the order cannot be forced from outside. Talks to the dev backend on 8080.
// Usage: node r1.mjs [rounds]   Exit code 1 if any round leaves someone signed in.
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
const base = 'http://127.0.0.1:8080'; const rounds = Number(process.argv[2] ?? 150);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const clear = () => execFileSync('docker', ['exec', 'apv-m1b-accept-dev', 'psql', '-U', 'apv', 'apv', '-Atc', 'delete from auth_throttle']);
async function call(method, path, body, token, cookie) {
  const r = await fetch(base + path, { method, headers: { ...(cookie ? { Cookie: cookie } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.getSetCookie().map(c => c.split(';')[0]).filter(c => !c.endsWith('='));
  return { status: r.status, cookie: set[0] ?? null, body: await r.json().catch(() => null) };
}
async function round(delay) {
  const email = `r1.${crypto.randomBytes(5).toString('hex')}@example.test`, p1 = 'Wd2 quartz-heron timber 9015', p2 = 'Wd2 quartz-heron timber 9016';
  let c = await call('GET', '/api/auth/csrf'); let cookie = c.cookie;
  await call('POST', '/api/auth/register', { email, password: p1, displayName: 'Race' }, c.body.token, cookie);
  c = await call('GET', '/api/auth/csrf', null, null, cookie); cookie = c.cookie ?? cookie;
  const login = await call('POST', '/api/auth/login', { email, password: p1 }, c.body.token, cookie);
  if (login.status !== 200) throw new Error('sign-in answered ' + login.status);
  cookie = login.cookie; const token = (await call('GET', '/api/auth/csrf', null, null, cookie)).body.token;
  const started = Date.now();
  const change = call('POST', '/api/auth/change-password', { currentPassword: p1, newPassword: p2 }, token, cookie).then(r => ({ ...r, took: Date.now() - started }));
  await sleep(delay);
  const out = await call('POST', '/api/auth/logout', null, token, cookie); const ch = await change;
  await sleep(300);
  const me = ch.cookie ? (await call('GET', '/api/me', null, null, ch.cookie)).status : null;
  return { delay, change: ch.status, took: ch.took, logout: out.status, me };
}
const tally = {}; let bad = 0, fiveHundred = 0, both204 = 0; const took = [];
let centre = 60;
for (let i = 0; i < rounds; i++) {
  clear();                                                               // sign-up is limited per address
  const r = await round(Math.max(5, centre - 12 + (i % 9) * 3)); took.push(r.took);
  if (i === 9) centre = Math.round(took.sort((a, b) => a - b)[5]);       // follow this machine's hash time
  const key = `change ${r.change}, sign-out ${r.logout}, new cookie on /me ${r.me}`; tally[key] = (tally[key] ?? 0) + 1;
  if (r.change >= 500 || r.logout >= 500) fiveHundred++;
  if (r.change === 204 && r.logout === 204) { both204++; if (r.me === 200) bad++; }
}
console.log(JSON.stringify({ rounds, medianChangeMs: took.sort((a, b) => a - b)[took.length >> 1], both204, leftSignedInAfterSignOut204: bad, fiveHundred, tally }, null, 1));
process.exit(bad || fiveHundred ? 1 : 0);
