// Does the dev profile's pinned clock affect identity? Real API calls against the dev backend.
import fs from 'node:fs'; import { totpAt } from './lib.mjs';
const log = fs.readFileSync(new URL('./evidence/backend-dev.log', import.meta.url), 'utf8');
const m = log.match(/admin\s+dev\.admin@example\.test\s+password: (\S+)\s+authenticator secret: (\S+)/); const [, password, secret] = m;
const base = 'http://127.0.0.1:8080'; let jar = '';
async function call(method, path, body, token) {
  const r = await fetch(base + path, { method, headers: { ...(jar ? { Cookie: jar } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.getSetCookie(); if (set.length) jar = set[0].split(';')[0];
  return { status: r.status, body: await r.json().catch(() => null) };
}
const csrf = async () => (await call('GET', '/api/auth/csrf')).body.token;
const login = await call('POST', '/api/auth/login', { email: 'dev.admin@example.test', password }, await csrf());
console.log('login', login.status, JSON.stringify(login.body));
const real = await call('POST', '/api/auth/mfa/verify', { method: 'totp', code: totpAt(secret, Date.now() / 1000) }, await csrf());
console.log('code for the real time now   ->', real.status, real.body?.error?.code ?? real.body?.status);
const pinned = await call('POST', '/api/auth/mfa/verify', { method: 'totp', code: totpAt(secret, Date.parse('2026-11-24T12:00:00Z') / 1000) }, await csrf());
console.log('code for the pinned instant  ->', pinned.status, pinned.body?.error?.code ?? pinned.body?.status);
const me = await call('GET', '/api/me'); console.log('me.session', JSON.stringify(me.body?.session), 'real now', new Date().toISOString());
