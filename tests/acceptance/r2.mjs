// R2: a sign-out carrying two values of the session cookie must end only the caller's own session.
// R3: a session cookie that is not an identifier is an unknown cookie, never a 500.
// Deterministic. Talks to the dev backend on 8080. Exit 1 if a victim is signed out or a 5xx is seen.
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
const base = 'http://127.0.0.1:8080';
const clear = () => execFileSync('docker', ['exec', 'apv-m1b-accept-dev', 'psql', '-U', 'apv', 'apv', '-Atc', 'delete from auth_throttle']);
async function call(method, path, body, token, cookie) {
  const r = await fetch(base + path, { method, headers: { ...(cookie ? { Cookie: cookie } : {}), ...(token ? { 'X-CSRF-TOKEN': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.getSetCookie().map(c => c.split(';')[0]).filter(c => !c.endsWith('='));
  return { status: r.status, cookie: set[0] ?? null, body: await r.json().catch(() => null) };
}
/** A signed-in donor: every identifier its session has had, oldest first, and its password. */
async function person(changes) {
  clear(); const email = `r2.${crypto.randomBytes(5).toString('hex')}@example.test`; let pw = 'Wd2 quartz-heron timber 9015'; const ids = [];
  let c = await call('GET', '/api/auth/csrf'); let cookie = c.cookie; ids.push(cookie);
  await call('POST', '/api/auth/register', { email, password: pw, displayName: 'R' }, c.body.token, cookie);
  c = await call('GET', '/api/auth/csrf', null, null, cookie);
  const login = await call('POST', '/api/auth/login', { email, password: pw }, c.body.token, cookie); if (login.status !== 200) throw new Error('sign-in ' + login.status);
  cookie = login.cookie; ids.push(cookie);
  for (let i = 0; i < changes; i++) { const t = (await call('GET', '/api/auth/csrf', null, null, cookie)).body.token; const next = pw.slice(0, -1) + (i + 6);
    const ch = await call('POST', '/api/auth/change-password', { currentPassword: pw, newPassword: next }, t, cookie); if (ch.status !== 204) throw new Error('change ' + ch.status); pw = next; cookie = ch.cookie; ids.push(cookie); }
  return { ids, live: cookie, token: (await call('GET', '/api/auth/csrf', null, null, cookie)).body.token };
}
const rows = []; let bad = 0;
for (const callerKind of ['anonymous', 'signed in']) for (const changes of [0, 1]) for (const order of ['retired first', 'retired last']) {
  const victim = await person(changes);
  for (const [which, retired] of victim.ids.slice(0, -1).entries()) {
    let caller; if (callerKind === 'anonymous') { const c = await call('GET', '/api/auth/csrf'); caller = { live: c.cookie, token: c.body.token }; } else caller = await person(0);
    const before = (await call('GET', '/api/me', null, null, victim.live)).status;
    const header = order === 'retired first' ? `${retired}; ${caller.live}` : `${caller.live}; ${retired}`;
    const out = await call('POST', '/api/auth/logout', null, caller.token, header);
    const after = (await call('GET', '/api/me', null, null, victim.live)).status;
    const ended = before === 200 && after !== 200; if (ended || out.status >= 500) bad++;
    rows.push(`${callerKind} caller, victim's retired identifier #${which} of ${victim.ids.length - 1}, ${order}: sign-out ${out.status}, victim ${before} -> ${after}${ended ? '  <-- VICTIM SIGNED OUT' : ''}`);
    if (ended) break;
  }
}
// The victim's live identifier with the caller's token, and a retired identifier alone, must be refused.
{ const victim = await person(0); const c = await call('GET', '/api/auth/csrf');
  const a = await call('POST', '/api/auth/logout', null, c.body.token, `${victim.live}; ${c.cookie}`); const b = await call('POST', '/api/auth/logout', null, c.body.token, victim.ids[0]);
  const after = (await call('GET', '/api/me', null, null, victim.live)).status; if (after !== 200 || a.status !== 403 || b.status !== 403) bad++;
  rows.push(`victim's live identifier first with the caller's token: ${a.status} ${a.body?.error?.code ?? ''}; retired identifier alone: ${b.status} ${b.body?.error?.code ?? ''}; victim then ${after}`); }
// R3
const name = (await call('GET', '/api/auth/csrf')).cookie.split('=')[0]; const odd = { 'null byte': 'AA==', 'text with null byte': Buffer.from('abc\0def').toString('base64'), 'not base64': '%%%', 'long': Buffer.from('x'.repeat(5000)).toString('base64'), 'upper-case identifier': Buffer.from(crypto.randomUUID().toUpperCase()).toString('base64') };
for (const [label, value] of Object.entries(odd)) { const statuses = [];
  for (const path of ['/api/site', '/api/me', '/api/auth/csrf', '/api/auth/options']) statuses.push((await call('GET', path, null, null, `${name}=${value}`)).status);
  statuses.push((await call('POST', '/api/auth/logout', null, 'x', `${name}=${value}`)).status);
  if (statuses.some(s => s >= 500)) bad++; rows.push(`cookie ${label}: site, me, csrf, options, POST logout -> ${statuses.join(', ')}`); }
console.log(rows.join('\n')); console.log(bad ? `DEFECT: ${bad} check(s) failed` : 'OK: no victim signed out, no 5xx'); process.exit(bad ? 1 : 0);
