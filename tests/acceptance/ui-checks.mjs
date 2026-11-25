// Presentation checks run on whatever identity screen a page is showing: the eight combinations of
// language, theme and width; contrast; keyboard order and visible focus; error semantics; autofill hints.
import { settle, sleep } from './lib.mjs';

const combos = []; for (const locale of ['en', 'fr']) for (const theme of ['light', 'dark']) for (const width of [360, 1440]) combos.push({ locale, theme, width });

/** Measured in the page: text contrast against the effective background, overflow, clipped controls. */
const measure = () => {
  const parse = c => { const m = c.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p[3] ?? 1 }; };
  const over = (top, under) => ({ r: top.r * top.a + under.r * (1 - top.a), g: top.g * top.a + under.g * (1 - top.a), b: top.b * top.a + under.b * (1 - top.a), a: 1 });
  const background = el => { const layers = []; for (let e = el; e; e = e.parentElement) { const c = parse(getComputedStyle(e).backgroundColor); if (c && c.a > 0) { layers.push(c); if (c.a === 1) break; } }
    let colour = { r: 255, g: 255, b: 255, a: 1 }; for (const l of layers.reverse()) colour = over(l, colour); return colour; };
  const lum = c => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
  const low = []; let min = 99;
  const panel = document.querySelector('.identity-panel'); if (!panel) return { missing: true };
  for (const el of panel.querySelectorAll('h1,p,label,small,a,button,dt,dd,li code,code,input')) {
    const style = getComputedStyle(el); const rect = el.getBoundingClientRect(); if (!rect.width || style.visibility === 'hidden') continue;
    if (el.tagName !== 'INPUT' && !el.textContent.trim()) continue; if (el.closest('svg')) continue;
    const bg = background(el); const fg = over(parse(style.color), bg); const [a, b] = [lum(fg), lum(bg)].sort((x, y) => y - x); const ratio = (a + 0.05) / (b + 0.05);
    const size = parseFloat(style.fontSize); const large = size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700); const need = large ? 3 : 4.5;
    min = Math.min(min, ratio); if (ratio < need) low.push(`${el.tagName.toLowerCase()}${el.className ? '.' + String(el.className).split(' ')[0] : ''} ${ratio.toFixed(2)}<${need}`);
  }
  const clipped = [...panel.querySelectorAll('input,button,a,code,h1,p,label')].filter(el => { const r = el.getBoundingClientRect(); return r.width && (r.right > innerWidth + 1 || r.left < -1 || el.scrollWidth > el.clientWidth + 2 && getComputedStyle(el).overflow !== 'visible'); })
    .map(el => el.tagName.toLowerCase() + ':' + (el.textContent || el.id).trim().slice(0, 30));
  const texts = [...panel.querySelectorAll('h1,p,label,small,a,button,dt,li')].map(e => [...e.childNodes].filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join(' ').trim()).filter(t => t.length > 2 && /[A-Za-zÀ-ÿ]{3}/.test(t));
  return { min, low, clipped, overflow: document.documentElement.scrollWidth > innerWidth + 1, lang: document.documentElement.lang, theme: document.documentElement.dataset.theme, texts, title: panel.querySelector('h1')?.textContent };
};

/**
 * Shows the current screen in all eight combinations by changing only the two stored preferences
 * and the window width, then reloading. Returns the problems found, each a short sentence.
 */
export async function sweep(p, page, label, { allowSame = [] } = {}) {
  const problems = []; const seen = {}; let loads = 0; let minContrast = 99;
  const violationsBefore = p.log.violations.length;
  for (const c of combos) {
    await page.setViewportSize({ width: c.width, height: 900 });
    await page.evaluate(({ locale, theme }) => { localStorage.setItem('apv-locale', locale); localStorage.setItem('apv-theme', theme); }, c);
    await page.reload({ waitUntil: 'domcontentloaded' }); await page.locator('.identity-panel').waitFor(); await settle(page); loads++;
    const m = await page.evaluate(measure); const at = `${label} ${c.locale}/${c.theme}/${c.width}`;
    if (m.missing) { problems.push(at + ': no identity panel'); continue; }
    if (m.lang !== c.locale) problems.push(`${at}: html lang is ${m.lang}`);
    if (m.theme !== c.theme) problems.push(`${at}: theme is ${m.theme}`);
    if (m.overflow) problems.push(`${at}: horizontal overflow`);
    if (m.clipped.length) problems.push(`${at}: clipped ${m.clipped.join(', ')}`);
    if (m.low.length) problems.push(`${at}: low contrast ${[...new Set(m.low)].join(', ')}`);
    minContrast = Math.min(minContrast, m.min); (seen[c.locale] ??= new Set()); m.texts.forEach(t => seen[c.locale].add(t));
  }
  const same = [...seen.en].filter(t => seen.fr.has(t) && !allowSame.some(a => t.includes(a)));
  if (same.length) problems.push(`${label}: identical text in English and French: ${same.map(t => JSON.stringify(t.slice(0, 50))).join(', ')}`);
  const violations = p.log.violations.slice(violationsBefore); if (violations.length) problems.push(`${label}: content-policy violations ${[...new Set(violations)].join(', ')}`);
  await page.setViewportSize({ width: 1280, height: 900 }); await page.evaluate(() => { localStorage.setItem('apv-locale', 'en'); localStorage.setItem('apv-theme', 'light'); });
  await page.reload({ waitUntil: 'domcontentloaded' }); await page.locator('.identity-panel').waitFor(); await settle(page);
  return { label, loads, problems, minContrast: Number(minContrast.toFixed(2)) };
}

/** Keyboard only: Tab reaches every control of the panel in document order, and each shows a focus indicator. */
export async function keyboard(page, label) {
  const problems = [];
  const expected = await page.evaluate(() => [...document.querySelectorAll('.identity-panel input, .identity-panel button, .identity-panel a')].filter(e => !e.disabled && e.getBoundingClientRect().width).map((e, i) => { e.dataset.kb = String(i); return i; }));
  await page.evaluate(() => { document.activeElement?.blur(); const p = document.querySelector('.identity-panel'); p.tabIndex = -1; p.focus(); p.removeAttribute('tabindex'); });
  const order = []; const unfocused = [];
  for (let i = 0; i < expected.length + 12 && order.length < expected.length; i++) {
    await page.keyboard.press('Tab');
    const f = await page.evaluate(() => { const e = document.activeElement; if (!e?.dataset?.kb) return null; const s = getComputedStyle(e);
      return { kb: Number(e.dataset.kb), visible: (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none', name: e.tagName.toLowerCase() + '#' + (e.id || e.textContent.trim().slice(0, 20)) }; });
    if (!f) continue; order.push(f.kb); if (!f.visible) unfocused.push(f.name);
  }
  if (JSON.stringify(order) !== JSON.stringify(expected)) problems.push(`${label}: Tab order ${order.join(',')} instead of ${expected.join(',')}`);
  if (unfocused.length) problems.push(`${label}: no visible focus on ${unfocused.join(', ')}`);
  return { label, controls: expected.length, problems };
}

/** Field semantics: label, autocomplete, paste allowed. */
export async function fields(page, label, expected) {
  const problems = [];
  const found = await page.evaluate(() => [...document.querySelectorAll('.identity-panel input')].map(e => {
    const paste = new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: new DataTransfer() }); const allowed = e.dispatchEvent(paste);
    return { id: e.id, type: e.type, autocomplete: e.autocomplete, label: document.querySelector(`label[for="${e.id}"]`)?.textContent ?? null, paste: allowed && !e.onpaste && !e.readOnly, required: e.required };
  }));
  for (const [id, autocomplete] of Object.entries(expected)) {
    const f = found.find(x => x.id === id); if (!f) { problems.push(`${label}: no field ${id}`); continue; }
    if (f.autocomplete !== autocomplete) problems.push(`${label}: ${id} has autocomplete "${f.autocomplete}", expected "${autocomplete}"`);
    if (!f.label) problems.push(`${label}: ${id} has no label`); if (!f.paste) problems.push(`${label}: pasting into ${id} is blocked`);
  }
  return { label, found, problems };
}

/** After an error is on screen: announced (role=alert or described-by), tied to its field, text and not colour alone, focus moved. */
export async function errorSemantics(page, label, fieldId) {
  await sleep(150);
  const r = await page.evaluate(id => { const f = document.getElementById(id); const ids = (f?.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean);
    const err = document.getElementById(id + '-error'); const alert = document.querySelector('.identity-panel [role=alert]');
    return { invalid: f?.getAttribute('aria-invalid'), described: ids.includes(id + '-error'), text: err?.textContent ?? '', alert: alert?.textContent ?? null, focused: document.activeElement?.id, live: err?.getAttribute('role') ?? err?.getAttribute('aria-live') ?? null }; }, fieldId);
  const problems = [];
  if (r.invalid !== 'true') problems.push(`${label}: ${fieldId} is not marked aria-invalid`);
  if (!r.described) problems.push(`${label}: the error is not tied to ${fieldId} by aria-describedby`);
  if (!r.text.trim()) problems.push(`${label}: no error text on ${fieldId}`);
  if (r.focused !== fieldId) problems.push(`${label}: focus is on "${r.focused}", not on ${fieldId}`);
  if (!r.alert && !r.live) problems.push(`${label}: the error appears without role=alert or a live region (a screen reader hears it only because focus moves to the described field)`);
  return { label, ...r, problems };
}
