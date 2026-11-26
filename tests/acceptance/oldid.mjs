// After a password change rotates the session identifier, is the old identifier still accepted for a moment?
import crypto from 'node:crypto';
const base = 'http://127.0.0.1:8080'; let jar = '';
async function call(method, path, body, token, cookie = jar) {
  const r = await fetch(base + path, { method, headers: { ...(cookie ? { Cookie: cookie } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.getSetCookie(); if (set.length && cookie === jar) jar = set[0].split(';')[0];
  return { status: r.status, body: await r.json().catch(() => null) };
}
const csrf = async () => (await call('GET', '/api/auth/csrf')).body.token;
const email = `race.${crypto.randomBytes(3).toString('hex')}@example.test`, p1 = 'Wd2 quartz-heron timber 9015', p2 = 'Wd2 quartz-heron timber 9016';
console.log('register', (await call('POST', '/api/auth/register', { email, password: p1, displayName: 'Race' }, await csrf())).status);
console.log('login', (await call('POST', '/api/auth/login', { email, password: p1 }, await csrf())).status);
const oldCookie = jar; const token = await csrf();
const change = call('POST', '/api/auth/change-password', { currentPassword: p1, newPassword: p2 }, token);
const during = []; for (let i = 0; i < 6; i++) during.push(call('GET', '/api/me', null, null, oldCookie).then(r => r.status));
console.log('change', (await change).status, '| old cookie GET /me issued concurrently:', (await Promise.all(during)).join(','));
const after = []; for (let i = 0; i < 5; i++) { after.push((await call('GET', '/api/me', null, null, oldCookie)).status); }
console.log('old cookie after the answer arrived:', after.join(','), '| new cookie:', (await call('GET', '/api/me')).status, '| rotated:', oldCookie !== jar);
