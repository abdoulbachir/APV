// Two tabs of one signed-in browser fetch the CSRF token at the same moment, right after sign-in
// (the session's token was replaced at sign-in and does not exist until first asked for).
import crypto from 'node:crypto';
const base = 'http://127.0.0.1:8080'; let jar = '';
async function call(method, path, body, token) {
  const r = await fetch(base + path, { method, headers: { ...(jar ? { Cookie: jar } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.getSetCookie(); if (set.length) jar = set[0].split(';')[0];
  return { status: r.status, body: await r.json().catch(() => null) };
}
const csrf = async () => (await call('GET', '/api/auth/csrf')).body.token;
const email = `tabs.${crypto.randomBytes(3).toString('hex')}@example.test`, pw = 'Fy3 marlin-cobble thicket 6120';
await call('POST', '/api/auth/register', { email, password: pw, displayName: 'Tabs' }, await csrf());
const tally = {}; let mismatch = 0;
for (let round = 0; round < 10; round++) {
  await call('POST', '/api/auth/login', { email, password: pw }, await csrf());
  const cookie = jar;                                              // signed in; no token stored yet
  const answers = await Promise.all([1, 2].map(() => fetch(base + '/api/auth/csrf', { headers: { Cookie: cookie } }).then(async r => ({ status: r.status, token: (await r.json().catch(() => ({}))).token }))));
  for (const a of answers) tally[a.status] = (tally[a.status] ?? 0) + 1;
  // does each token that was handed out actually work?
  for (const a of answers.filter(a => a.token)) { const r = await fetch(base + '/api/auth/login', { method: 'POST', headers: { Cookie: cookie, 'X-CSRF-TOKEN': a.token, 'Content-Type': 'application/json' }, body: '{"email":"x@example.test","password":"xxxxxxxxxxxxxxxx"}' }); if (r.status === 403) mismatch++; }
  await call('POST', '/api/auth/logout', null, await csrf()); jar = '';
}
console.log('10 rounds of 2 simultaneous GET /auth/csrf right after sign-in -> statuses', JSON.stringify(tally), '| tokens handed out with 200 that the server then refuses:', mismatch);
