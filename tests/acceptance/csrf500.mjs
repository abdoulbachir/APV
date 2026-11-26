// Minimal reproduction: an anonymous client, one real token, then POSTs carrying tokens of the same
// length and alphabet with a few characters changed. No session, no credentials.
import crypto from 'node:crypto';
const base = process.argv[2] ?? 'http://127.0.0.1:8080';
const first = await fetch(base + '/api/auth/csrf'); const cookie = first.headers.getSetCookie()[0].split(';')[0]; const { token } = await first.json();
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'; const tally = {};
const post = async (t, path = '/api/auth/logout') => { const r = await fetch(base + path, { method: 'POST', headers: { Cookie: cookie, 'X-CSRF-TOKEN': t } }); const b = await r.json().catch(() => ({})); return r.status + ' ' + (b.error?.code ?? ''); };
console.log('token length', token.length, '| genuine token on logout ->', await post(token));
const again = await fetch(base + '/api/auth/csrf'); const c2 = again.headers.getSetCookie()[0].split(';')[0]; const t2 = (await again.json()).token;
for (let i = 0; i < 40; i++) {
  const chars = [...t2]; for (let k = 0; k < 4; k++) chars[crypto.randomInt(chars.length)] = alphabet[crypto.randomInt(64)];
  const r = await fetch(base + '/api/auth/login', { method: 'POST', headers: { Cookie: c2, 'X-CSRF-TOKEN': chars.join(''), 'Content-Type': 'application/json' }, body: '{"email":"a@example.test","password":"xxxxxxxxxxxxxxxx"}' });
  const b = await r.json().catch(() => ({})); const key = r.status + ' ' + (b.error?.code ?? '(no error body)'); tally[key] = (tally[key] ?? 0) + 1;
}
console.log('40 POST /auth/login with a corrupted token of valid shape ->', JSON.stringify(tally));
const other = await (await fetch(base + '/api/auth/csrf')).json();
const r = await fetch(base + '/api/auth/login', { method: 'POST', headers: { Cookie: c2, 'X-CSRF-TOKEN': other.token, 'Content-Type': 'application/json' }, body: '{"email":"a@example.test","password":"xxxxxxxxxxxxxxxx"}' });
console.log('a genuine token of another session ->', r.status, (await r.json()).error.code);
