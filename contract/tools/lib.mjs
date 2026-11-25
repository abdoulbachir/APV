// Shared code for the contract checks.
//
// The second half of this file is a small reference implementation of the public read rules
// (totals, corrections, donor list, search, sorting, paging). It exists so the examples in
// openapi.yaml and the expected values of the fixtures are checked by computation instead of
// by eye. It is not production code.

import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import YAML from 'yaml';

const require = createRequire(import.meta.url);
const Ajv2020 = require('ajv/dist/2020');
const addFormats = require('ajv-formats');

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const readText = rel => readFileSync(path.join(root, rel), 'utf8');
export const readJson = rel => JSON.parse(readText(rel));
export const clone = value => structuredClone(value);

// ---------------------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------------------

export function newAjv() {
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  return ajv;
}

/** Applies a list of { op: add | replace | remove, path, value } operations (JSON Pointer paths). */
export function applyPatch(document, operations) {
  const result = clone(document);
  for (const { op, path: pointer, value } of operations) {
    const parts = pointer.split('/').slice(1).map(p => p.replace(/~1/g, '/').replace(/~0/g, '~'));
    const last = parts.pop();
    let target = result;
    for (const part of parts) {
      if (target === null || typeof target !== 'object' || !(part in target)) throw new Error(`patch path not found: ${pointer}`);
      target = target[part];
    }
    if (op === 'remove') {
      if (!(last in target)) throw new Error(`patch path not found: ${pointer}`);
      if (Array.isArray(target)) target.splice(Number(last), 1);
      else delete target[last];
    } else if (op === 'replace') {
      if (!(last in target)) throw new Error(`patch path not found: ${pointer}`);
      target[last] = value;
    } else if (op === 'add') {
      if (Array.isArray(target)) target.splice(last === '-' ? target.length : Number(last), 0, value);
      else target[last] = value;
    } else {
      throw new Error(`unknown patch operation: ${op}`);
    }
  }
  return result;
}

/** Stable JSON text with sorted keys, for comparing two values. */
export function stable(value) {
  return JSON.stringify(value, (key, v) =>
    v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v, 2);
}

export const sameValue = (a, b) => stable(a) === stable(b);

/** Codes and identifiers are ASCII; they are compared by code point, with no locale rules. */
const compareText = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

export function isValidTimezone(zone) {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return typeof zone === 'string' && zone.includes('/');
  } catch {
    return false;
  }
}

function zoneOffsetMs(zone, instantMs) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).formatToParts(new Date(instantMs)).map(p => [p.type, p.value]));
  return Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second) - instantMs;
}

const toTimestamp = ms => new Date(ms).toISOString().replace('.000Z', 'Z');

/** First instant of the day after `date` in `zone`, using that zone's daylight saving rules. */
export function deadlineEndsAt(date, zone) {
  if (date === null) return null;
  const [year, month, day] = date.split('-').map(Number);
  const wallClock = Date.UTC(year, month - 1, day + 1);
  let instant = wallClock - zoneOffsetMs(zone, wallClock);
  instant = wallClock - zoneOffsetMs(zone, instant);
  return toTimestamp(instant);
}

// ---------------------------------------------------------------------------------------
// Ledger rules (contract/financial-terms.md)
// ---------------------------------------------------------------------------------------

export const KINDS = {
  donation: { allocated: 1, delivered: 0 },
  donation_reversal: { allocated: -1, delivered: 0, corrects: 'donation' },
  refund: { allocated: -1, delivered: 0, corrects: 'donation' },
  transfer_in: { allocated: 1, delivered: 0, pairedWith: 'transfer_out' },
  transfer_out: { allocated: -1, delivered: 0, pairedWith: 'transfer_in', discretionary: true },
  transfer_in_reversal: { allocated: -1, delivered: 0, corrects: 'transfer_in', pairedWith: 'transfer_out_reversal', discretionary: true },
  transfer_out_reversal: { allocated: 1, delivered: 0, corrects: 'transfer_out', pairedWith: 'transfer_in_reversal' },
  disbursement: { allocated: 0, delivered: 1, discretionary: true },
  disbursement_reversal: { allocated: 0, delivered: -1, corrects: 'disbursement' },
};

export const MAX_PLATFORM_GROSS = 10n ** 15n;

const chronological = (a, b) => compareText(a.date, b.date) || compareText(a.id, b.id);
const newestFirst = (a, b) => compareText(b.date, a.date) || compareText(b.id, a.id);

/** For every entry id: the entries correcting it (oldest first) and their summed amount. */
export function corrections(fixture) {
  const map = new Map(fixture.ledger.map(e => [e.id, { correctedBy: [], correctedAmount: 0 }]));
  for (const entry of [...fixture.ledger].sort(chronological)) {
    if (entry.corrects !== null && map.has(entry.corrects)) {
      const target = map.get(entry.corrects);
      target.correctedBy.push(entry.id);
      target.correctedAmount += entry.amount;
    }
  }
  return map;
}

/** Cross-record checks that a JSON Schema cannot express. Returns a list of { code, message }. */
export function validateIntegrity(fixture, { maxPlatformGross = MAX_PLATFORM_GROSS } = {}) {
  const errors = [];
  const fail = (code, message) => errors.push({ code, message });
  const duplicates = (values, what) => {
    const seen = new Set();
    for (const value of values) {
      if (seen.has(value)) fail('duplicate-id', `duplicate ${what}: ${value}`);
      seen.add(value);
    }
  };

  // Identifiers
  duplicates(fixture.categories.map(c => c.id), 'category id');
  duplicates(fixture.cases.map(c => c.code), 'case code');
  duplicates(fixture.ledger.map(e => e.id), 'ledger id');
  duplicates(fixture.updates.map(u => u.id), 'update id');
  duplicates(fixture.ledger.filter(e => e.donor).map(e => e.donor.listingId), 'donor listing id');
  for (const c of fixture.cases) duplicates(c.photos.map(p => p.id), `photo id on ${c.code}`);

  // Categories
  const positions = fixture.categories.map(c => c.position);
  if (new Set(positions).size !== positions.length) fail('category-position', 'two categories share a position');
  const categoryIds = new Set(fixture.categories.map(c => c.id));
  if (!isValidTimezone(fixture.timezone)) fail('timezone', `unknown organization timezone: ${fixture.timezone}`);

  // Cases
  const funds = fixture.cases.filter(c => c.kind === 'fund');
  if (funds.length !== 1 || funds[0].code !== 'FUND' || funds[0].status === 'draft') fail('fund', 'exactly one public fund with code FUND is required');
  const caseByCode = new Map(fixture.cases.map(c => [c.code, c]));
  for (const c of fixture.cases) {
    if (c.kind === 'fund' ? c.category !== null : c.category === null) fail('category-kind', `${c.code}: a case needs a category and the fund has none`);
    if (c.category !== null && !categoryIds.has(c.category)) fail('unknown-category', `${c.code}: unknown category ${c.category}`);
    if ((c.deadline === null) !== (c.deadlineTimezone === null)) fail('deadline', `${c.code}: deadline and deadlineTimezone must be set together`);
    else if (c.deadlineTimezone !== null && !isValidTimezone(c.deadlineTimezone)) fail('deadline', `${c.code}: unknown timezone ${c.deadlineTimezone}`);
    if (c.kind === 'fund' && (c.featuredRank !== null || c.goal !== null || c.deadline !== null)) fail('fund', 'the fund has no rank, goal or deadline');
    const photoPositions = c.photos.map(p => p.position).sort((a, b) => a - b);
    if (photoPositions.some((p, i) => p !== i + 1)) fail('photo-position', `${c.code}: gallery positions must be 1..n`);
  }
  const ranks = fixture.cases.filter(c => c.featuredRank !== null).map(c => c.featuredRank);
  if (new Set(ranks).size !== ranks.length) fail('featured-rank', 'two cases share a featured rank');
  for (const update of fixture.updates) {
    if (!caseByCode.has(update.caseCode)) fail('unknown-case', `update ${update.id}: unknown case ${update.caseCode}`);
  }

  // Ledger entries, one by one
  const entryById = new Map(fixture.ledger.map(e => [e.id, e]));
  let gross = 0n;
  for (const entry of fixture.ledger) {
    const rule = KINDS[entry.kind];
    gross += BigInt(entry.amount);
    const owner = caseByCode.get(entry.caseCode);
    if (!owner) fail('unknown-case', `${entry.id}: unknown case ${entry.caseCode}`);
    else if (owner.status === 'draft') fail('ledger-on-draft', `${entry.id}: money cannot move on draft case ${entry.caseCode}`);
    if ((entry.kind === 'donation') !== (entry.donor !== undefined)) fail('donor-kind', `${entry.id}: a donor block belongs on donations and only there`);
    if (Boolean(rule.pairedWith) !== (entry.pairId !== null)) fail('transfer-pair', `${entry.id}: pairId belongs on transfer kinds and only there`);

    if (!rule.corrects) {
      if (entry.corrects !== null) fail('correction-reference', `${entry.id}: an original movement cannot correct another entry`);
      continue;
    }
    if (entry.corrects === null) { fail('correction-reference', `${entry.id}: a ${entry.kind} must name the entry it corrects`); continue; }
    const target = entryById.get(entry.corrects);
    if (!target) { fail('correction-target', `${entry.id}: corrects missing entry ${entry.corrects}`); continue; }
    if (target.kind !== rule.corrects) fail('correction-kind', `${entry.id}: a ${entry.kind} can only correct a ${rule.corrects}, not a ${target.kind}`);
    if (target.caseCode !== entry.caseCode) fail('correction-case', `${entry.id}: corrects an entry of another case`);
    if (entry.date < target.date) fail('correction-date', `${entry.id}: dated before the entry it corrects`);
  }
  if (gross > maxPlatformGross) fail('aggregate-bound', `sum of all movement amounts ${gross} exceeds ${maxPlatformGross}`);

  // Cumulative correction limit
  for (const [id, { correctedBy, correctedAmount }] of corrections(fixture)) {
    const entry = entryById.get(id);
    if (correctedAmount > entry.amount) fail('correction-limit', `${id}: corrections ${correctedBy.join(', ')} total ${correctedAmount}, more than the original ${entry.amount}`);
  }

  // Transfer pairing: every pairId names exactly two entries, opposite kinds, same amount and
  // date, different cases. A reversal pair corrects the two sides of one original transfer.
  const pairs = new Map();
  for (const entry of fixture.ledger) {
    if (entry.pairId !== null) pairs.set(entry.pairId, [...(pairs.get(entry.pairId) ?? []), entry]);
  }
  for (const [pairId, entries] of pairs) {
    const [a, b] = entries;
    const wellFormed = entries.length === 2 && KINDS[a.kind].pairedWith === b.kind && a.amount === b.amount && a.date === b.date && a.caseCode !== b.caseCode;
    if (!wellFormed) { fail('transfer-pair', `pair ${pairId}: needs two opposite entries with the same amount and date on two different cases`); continue; }
    if (KINDS[a.kind].corrects) {
      const targetA = entryById.get(a.corrects);
      const targetB = entryById.get(b.corrects);
      if (!targetA || !targetB || targetA.pairId === null || targetA.pairId !== targetB.pairId) fail('transfer-pair', `pair ${pairId}: a transfer reversal must correct both sides of one transfer`);
    }
  }

  // No discretionary spending beyond what a case holds. Refunds and donation reversals are
  // real events and are always recorded, even when they create a shortfall.
  const available = new Map();
  for (const entry of [...fixture.ledger].sort(chronological)) {
    const rule = KINDS[entry.kind];
    const next = (available.get(entry.caseCode) ?? 0) + (rule.allocated - rule.delivered) * entry.amount;
    if (rule.discretionary && next < 0) fail('overspend', `${entry.id}: a ${entry.kind} of ${entry.amount} would leave ${entry.caseCode} at ${next}`);
    available.set(entry.caseCode, next);
  }

  // Reconciliation
  const totals = financials(fixture);
  let transferNet = 0;
  for (const entry of fixture.ledger) if (KINDS[entry.kind].pairedWith) transferNet += KINDS[entry.kind].allocated * entry.amount;
  if (transferNet !== 0) fail('reconciliation', `transfers do not cancel out: ${transferNet}`);
  for (const [code, f] of totals) {
    if (f.delivered < 0 || f.externalDonations < 0 || f.transfersIn < 0 || f.transfersOut < 0) fail('reconciliation', `${code}: a net total is negative`);
  }
  return errors;
}

/** Per-case money breakdown, computed from the ledger only. */
export function financials(fixture) {
  const result = new Map(fixture.cases.map(c => [c.code, { externalDonations: 0, transfersIn: 0, transfersOut: 0, allocated: 0, delivered: 0, available: 0, hasShortfall: false }]));
  for (const entry of fixture.ledger) {
    const f = result.get(entry.caseCode);
    if (!f) continue;
    const { kind, amount } = entry;
    if (kind === 'donation') f.externalDonations += amount;
    if (kind === 'donation_reversal' || kind === 'refund') f.externalDonations -= amount;
    if (kind === 'transfer_in') f.transfersIn += amount;
    if (kind === 'transfer_in_reversal') f.transfersIn -= amount;
    if (kind === 'transfer_out') f.transfersOut += amount;
    if (kind === 'transfer_out_reversal') f.transfersOut -= amount;
    f.delivered += KINDS[kind].delivered * amount;
  }
  for (const f of result.values()) {
    f.allocated = f.externalDonations + f.transfersIn - f.transfersOut;
    f.available = f.allocated - f.delivered;
    f.hasShortfall = f.available < 0;
  }
  return result;
}

export function stats(fixture) {
  const totals = financials(fixture);
  let received = 0;
  let delivered = 0;
  let peopleHelped = 0;
  for (const c of fixture.cases) {
    const f = totals.get(c.code);
    received += f.externalDonations;
    delivered += f.delivered;
    if (c.kind === 'case' && f.delivered > 0) peopleHelped += 1;
  }
  return { received, delivered, available: received - delivered, peopleHelped, asOf: fixture.clock };
}

// ---------------------------------------------------------------------------------------
// Reference implementation of the public responses
// ---------------------------------------------------------------------------------------

const isPublic = c => c.status !== 'draft';
const otherLocale = locale => (locale === 'en' ? 'fr' : 'en');

function localized(text, locale) {
  if (text === null || text === undefined) return [null, null];
  return text[locale] !== undefined ? [text[locale], locale] : [text[otherLocale(locale)], otherLocale(locale)];
}

/** A stand-in for the random token the real server stores with each public picture. */
function mediaPath(file, variant) {
  if (!file) return null;
  const token = createHash('sha256').update(file).digest('base64url').slice(0, 22);
  return `/media/public/${token}/${variant}.webp`;
}

export function site(fixture) {
  const used = new Set(fixture.cases.filter(isPublic).map(c => c.category));
  return {
    timezone: fixture.timezone,
    currency: 'USD',
    locales: ['en', 'fr'],
    defaultLocale: 'en',
    categories: fixture.categories
      .filter(c => c.active || used.has(c.id))
      .sort((a, b) => a.position - b.position || compareText(a.id, b.id))
      .map(({ id, labels, position, active }) => ({ id, labels, position, active })),
    fundCode: 'FUND',
    pagination: { defaultPageSize: 12, maxPageSize: 48 },
  };
}

function publicCase(fixture, c, locale, f) {
  const [title, titleLanguage] = localized(c.title, locale);
  const [story, storyLanguage] = localized(c.story, locale);
  const [photoAlt, photoAltLanguage] = localized(c.cover?.alt, locale);
  const endsAt = deadlineEndsAt(c.deadline, c.deadlineTimezone);
  return {
    code: c.code,
    kind: c.kind,
    status: c.status,
    acceptingDonations: c.acceptsDonations && ['active', 'funded'].includes(c.status) && (endsAt === null || fixture.clock < endsAt),
    category: c.category,
    featuredRank: c.featuredRank,
    title,
    titleLanguage,
    publicName: c.publicName,
    story,
    storyLanguage,
    photoUrl: mediaPath(c.cover?.file, 'display'),
    photoThumbnailUrl: mediaPath(c.cover?.file, 'thumb'),
    photoAlt,
    photoAltLanguage,
    goal: c.goal,
    received: f.allocated,
    delivered: f.delivered,
    deadline: c.deadline,
    deadlineTimezone: c.deadlineTimezone,
    deadlineEndsAt: endsAt,
    createdAt: c.createdAt,
  };
}

function publicLedger(fixture, code, locale) {
  const corrected = corrections(fixture);
  return fixture.ledger.filter(e => e.caseCode === code).sort(newestFirst).map(entry => {
    const rule = KINDS[entry.kind];
    const { correctedBy, correctedAmount } = corrected.get(entry.id);
    const counterpart = entry.pairId === null ? null : fixture.ledger.find(e => e.pairId === entry.pairId && e.id !== entry.id);
    return {
      id: entry.id,
      kind: entry.kind,
      amount: entry.amount,
      allocatedDelta: rule.allocated * entry.amount,
      deliveredDelta: rule.delivered * entry.amount,
      date: entry.date,
      label: fixture.ledgerLabels[entry.kind][locale],
      corrects: entry.corrects,
      correctedBy,
      correctedAmount,
      netAmount: entry.amount - correctedAmount,
      counterpartCaseCode: counterpart ? counterpart.caseCode : null,
    };
  });
}

function publicDonors(fixture, code) {
  const corrected = corrections(fixture);
  return fixture.ledger
    .filter(e => e.caseCode === code && e.kind === 'donation' && e.donor.visibility !== 'hidden')
    .map(e => ({ entry: e, net: e.amount - corrected.get(e.id).correctedAmount }))
    .filter(({ net }) => net > 0)
    .sort((a, b) => compareText(b.entry.date, a.entry.date) || compareText(b.entry.donor.listingId, a.entry.donor.listingId))
    .map(({ entry, net }) => {
      const named = entry.donor.visibility === 'named';
      return {
        id: entry.donor.listingId,
        name: named ? entry.donor.name : null,
        amount: net,
        date: entry.date,
        avatarUrl: named && entry.donor.avatarConsent ? mediaPath(entry.donor.avatarFile, 'thumb') : null,
      };
    });
}

function publicUpdates(fixture, code, locale) {
  return fixture.updates.filter(u => u.caseCode === code).sort(newestFirst).map(update => {
    const [text, textLanguage] = localized(update.text, locale);
    const [photoAlt, photoAltLanguage] = localized(update.photo?.alt, locale);
    return {
      id: update.id,
      caseCode: update.caseCode,
      text,
      textLanguage,
      photoUrl: mediaPath(update.photo?.file, 'display'),
      photoAlt,
      photoAltLanguage,
      date: update.date,
      author: update.author,
      authorAvatarUrl: update.authorAvatarConsent ? mediaPath(update.authorAvatarFile, 'thumb') : null,
    };
  });
}

export function paginate(items, page = 1, pageSize = 12) {
  return {
    items: items.slice((page - 1) * pageSize, page * pageSize),
    page,
    pageSize,
    totalItems: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / pageSize)),
  };
}

const findPublicCase = (fixture, code) => fixture.cases.find(c => c.code === code.toUpperCase() && isPublic(c)) ?? null;

/** GET /cases/{code}. Returns null for a draft or unknown code (404 case_not_found). */
export function caseDetail(fixture, code, locale = 'en') {
  const c = findPublicCase(fixture, code);
  if (!c) return null;
  const updates = publicUpdates(fixture, c.code, locale);
  const donors = publicDonors(fixture, c.code);
  const ledger = publicLedger(fixture, c.code, locale);
  const f = financials(fixture).get(c.code);
  return {
    case: publicCase(fixture, c, locale, f),
    financials: f,
    photos: [...c.photos].sort((a, b) => a.position - b.position).map(photo => {
      const [alt, altLanguage] = localized(photo.alt, locale);
      return { id: photo.id, url: mediaPath(photo.file, 'display'), thumbnailUrl: mediaPath(photo.file, 'thumb'), alt, altLanguage, position: photo.position };
    }),
    updates: updates.slice(0, 10),
    donors: donors.slice(0, 10),
    ledger: ledger.slice(0, 10),
    counts: { updates: updates.length, donors: donors.length, ledger: ledger.length, photos: c.photos.length },
    asOf: fixture.clock,
  };
}

export const caseUpdates = (fixture, code, locale, page, pageSize) => paginate(publicUpdates(fixture, code, locale), page, pageSize);
export const caseDonors = (fixture, code, page, pageSize) => paginate(publicDonors(fixture, code), page, pageSize);
export const caseLedger = (fixture, code, locale, page, pageSize) => paginate(publicLedger(fixture, code, locale), page, pageSize);

/** The search normalization of GET /cases. */
export const normalize = text => text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();

/** Compares received/goal of two cases exactly. Both must have a goal. */
function compareRatio(a, b) {
  const left = BigInt(Math.max(a.received, 0)) * BigInt(b.goal);
  const right = BigInt(Math.max(b.received, 0)) * BigInt(a.goal);
  return left < right ? -1 : left > right ? 1 : 0;
}

const tieBreak = (a, b) => compareText(b.createdAt, a.createdAt) || compareText(a.code, b.code);
const nullsLast = (a, b, compare) => (a === null ? (b === null ? 0 : 1) : b === null ? -1 : compare(a, b));

const SORTS = {
  newest: () => 0,
  deadline: (a, b) => nullsLast(a.deadlineEndsAt, b.deadlineEndsAt, compareText),
  closest: (a, b) => {
    const group = c => (c.goal === null ? 2 : c.received < c.goal ? 0 : 1);
    if (group(a) !== group(b)) return group(a) - group(b);
    return group(a) === 0 ? compareRatio(b, a) : group(a) === 1 ? compareRatio(a, b) : 0;
  },
  least: (a, b) => nullsLast(a.goal, b.goal, () => compareRatio(a, b)),
  featured: (a, b) => nullsLast(a.featuredRank, b.featuredRank, (x, y) => x - y),
};

/** GET /cases. */
export function listCases(fixture, { status = 'active', q = '', category = null, sort = 'newest', page = 1, pageSize = 12, locale = 'en' } = {}) {
  const group = status === 'active' ? ['active', 'funded'] : ['delivered', 'closed'];
  const query = normalize(q);
  const totals = financials(fixture);
  const items = fixture.cases
    .filter(c => c.kind === 'case' && group.includes(c.status))
    .filter(c => category === null || c.category === category)
    .filter(c => query === '' || [c.title.en, c.title.fr, c.publicName, c.code].some(field => normalize(field).includes(query)))
    .map(c => publicCase(fixture, c, locale, totals.get(c.code)))
    .sort((a, b) => SORTS[sort](a, b) || tieBreak(a, b));
  return paginate(items, page, pageSize);
}

// ---------------------------------------------------------------------------------------
// Expected values and the public projection
// ---------------------------------------------------------------------------------------

/** Every string that must never appear in a public response or in the public projection. */
export function forbiddenMarkers(fixture) {
  const markers = new Set();
  for (const c of fixture.cases) {
    Object.values(c.private).forEach(v => markers.add(v));
    if (!isPublic(c)) [c.publicName, c.title.en, c.title.fr, c.story.en, c.story.fr].forEach(v => markers.add(v));
  }
  for (const entry of fixture.ledger) {
    if (entry.donor && entry.donor.visibility !== 'named' && entry.donor.name) markers.add(entry.donor.name);
  }
  markers.delete('');
  return [...markers].sort();
}

/** The values example.expected.json must hold. `directoryQueries` are query strings. */
export function expectedValues(fixture, directoryQueries) {
  const corrected = corrections(fixture);
  const cases = {};
  for (const c of fixture.cases) {
    const detail = caseDetail(fixture, c.code);
    if (!detail) { cases[c.code] = '404 case_not_found'; continue; }
    cases[c.code] = {
      acceptingDonations: detail.case.acceptingDonations,
      deadlineEndsAt: detail.case.deadlineEndsAt,
      received: detail.case.received,
      delivered: detail.case.delivered,
      financials: detail.financials,
      counts: detail.counts,
      donors: caseDonors(fixture, c.code, 1, 48).items.map(d => ({ id: d.id, name: d.name, amount: d.amount, hasAvatar: d.avatarUrl !== null })),
      corrected: Object.fromEntries(fixture.ledger
        .filter(e => e.caseCode === c.code && corrected.get(e.id).correctedBy.length > 0)
        .map(e => [e.id, { ...corrected.get(e.id), netAmount: e.amount - corrected.get(e.id).correctedAmount }])),
    };
  }
  const { asOf, ...platform } = stats(fixture);
  return {
    site: { timezone: fixture.timezone, categories: site(fixture).categories.map(c => ({ id: c.id, active: c.active })) },
    stats: platform,
    cases,
    directory: Object.fromEntries(directoryQueries.map(queryString => {
      const params = Object.fromEntries(new URLSearchParams(queryString));
      return [queryString, listCases(fixture, { ...params, pageSize: 48 }).items.map(c => c.code)];
    })),
    mustNeverAppearInAnyPublicResponse: forbiddenMarkers(fixture),
  };
}

/** The sanitized dataset the frontend mock may bundle. Same inputs, minus everything private. */
export function projectPublic(fixture) {
  const publicCodes = new Set(fixture.cases.filter(isPublic).map(c => c.code));
  const listed = new Set(site(fixture).categories.map(c => c.id));
  return {
    version: fixture.version,
    clock: fixture.clock,
    timezone: fixture.timezone,
    categories: fixture.categories.filter(c => listed.has(c.id)),
    ledgerLabels: fixture.ledgerLabels,
    cases: fixture.cases.filter(isPublic).map(({ private: _private, ...rest }) => rest),
    ledger: fixture.ledger.filter(e => publicCodes.has(e.caseCode)).map(({ donor, ...rest }) => {
      if (!donor || donor.visibility === 'hidden') return rest;
      const named = donor.visibility === 'named';
      return { ...rest, donor: { listingId: donor.listingId, name: named ? donor.name : null, avatarFile: named && donor.avatarConsent ? donor.avatarFile : null } };
    }),
    updates: fixture.updates.filter(u => publicCodes.has(u.caseCode)).map(({ authorAvatarConsent, authorAvatarFile, ...rest }) => ({
      ...rest,
      authorAvatarFile: authorAvatarConsent ? authorAvatarFile : null,
    })),
  };
}

// ---------------------------------------------------------------------------------------
// The OpenAPI document
// ---------------------------------------------------------------------------------------

export function loadContract() {
  const document = YAML.parse(readText('openapi.yaml'));
  const bundle = JSON.parse(JSON.stringify({ $id: 'https://apv-gakasinay.invalid/openapi', $defs: document.components.schemas })
    .replaceAll('#/components/schemas/', '#/$defs/'));
  const ajv = newAjv();
  ajv.addSchema(bundle);
  const validators = new Map();
  /** Validates a value against a schema object from the document. Returns a list of error texts. */
  const check = (schema, value) => {
    const key = JSON.stringify(schema);
    if (!validators.has(key)) {
      const rewritten = JSON.parse(key.replaceAll('#/components/schemas/', 'https://apv-gakasinay.invalid/openapi#/$defs/'));
      validators.set(key, ajv.compile(rewritten));
    }
    const validate = validators.get(key);
    return validate(value) ? [] : validate.errors.map(e => `${e.instancePath || '/'} ${e.message}`);
  };
  const checkNamed = (name, value) => check({ $ref: `#/components/schemas/${name}` }, value);
  const resolve = pointer => pointer.slice(2).split('/').reduce((node, part) => node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], document);
  const operations = Object.entries(document.paths).flatMap(([route, item]) => Object.entries(item).map(([method, operation]) => ({ route, method, operation })));
  const exampleOf = operationId => {
    const found = operations.find(o => o.operation.operationId === operationId);
    if (!found) throw new Error(`no operation ${operationId}`);
    const media = found.operation.responses['200'].content['application/json'];
    return { example: media.example, schema: media.schema };
  };
  return { document, check, checkNamed, resolve, operations, exampleOf };
}
