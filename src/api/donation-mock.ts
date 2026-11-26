// In-memory mock of the giving flow, for building and browser-testing the UI without a backend.
// It is loaded only in mock mode (VITE_API_MODE=mock) and never in a real build. It holds a couple
// of sample payment methods and keeps reported gifts in memory so the receipt and status pages work.
import { APP_CONFIG } from '../config';
import { ApiError } from './client';
import type { DonationClient } from './donation-client';
import type { DonationOperations } from './generated/identity-api';
import type { Locale } from './public-types';

type Receipt = DonationOperations['reportDonation']['response'];
type Status = DonationOperations['getDonationStatus']['response'];

const methods: DonationOperations['listPaymentMethods']['response'] = {
  methods: [
    { id: 'zelle', position: 1, detailsId: 'zelle-v1', labels: { en: 'Zelle', fr: 'Zelle' },
      instructions: { en: 'Send with Zelle to the number below, then report your gift here.', fr: 'Envoyez avec Zelle au numéro ci-dessous, puis déclarez votre don ici.' },
      recipient: [{ labels: { en: 'Zelle phone number', fr: 'Numéro Zelle' }, value: '+1 555 0100 (sample)', format: 'text' }] },
    { id: 'paypal', position: 2, detailsId: 'paypal-v1', labels: { en: 'PayPal', fr: 'PayPal' },
      instructions: { en: 'Open the PayPal link, send your gift, then report it here.', fr: 'Ouvrez le lien PayPal, envoyez votre don, puis déclarez-le ici.' },
      recipient: [{ labels: { en: 'PayPal link', fr: 'Lien PayPal' }, value: 'https://paypal.me/sample', format: 'link' }] },
  ],
};

const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // no I, L, O, U
const group = (n: number) => Array.from({ length: n }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join('');

// The real backend persists gifts; the mock keeps them in sessionStorage so the receipt and status
// pages work across a reload or a new tab during a testing session. Mock mode only.
type StoredGift = Status & { trackingCode: string };
const STORE_KEY = 'apv-mock-donations';
const load = (): Record<string, StoredGift> => { try { return JSON.parse(sessionStorage.getItem(STORE_KEY) ?? '{}'); } catch { return {}; } };
const save = (gift: StoredGift) => { const all = load(); all[gift.trackingCode.replace(/-/g, '')] = gift; sessionStorage.setItem(STORE_KEY, JSON.stringify(all)); };

async function delay<T>(value: T): Promise<T> {
  await new Promise(r => window.setTimeout(r, APP_CONFIG.mockDelayMs));
  return structuredClone(value);
}

export const mockDonationClient: DonationClient = {
  listMethods: () => delay(methods),
  async report(body) {
    const reference = `${group(4)}-${group(4)}`;
    const trackingCode = `${reference}-${group(4)}-${group(4)}-${group(4)}`;
    const reportedAt = new Date().toISOString();
    save({
      trackingCode, reference, state: 'reported', caseCode: body.caseCode.toUpperCase(),
      reportedAmount: body.amount, reportedAt, decidedAt: null, confirmedAmount: null,
      confirmedCaseCode: null, netAmount: null, rejectionReason: null, evidenceAttached: false,
    });
    const receipt: Receipt = {
      reference, trackingCode, state: 'reported', caseCode: body.caseCode.toUpperCase(), amount: body.amount, reportedAt,
      evidenceUpload: { token: 'mock-' + reference, expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(), maxBytes: 5242880, formats: ['image/jpeg', 'image/png'] },
    };
    return delay(receipt);
  },
  async attachEvidence(token) {
    for (const gift of Object.values(load())) if (token === 'mock-' + gift.reference) { gift.evidenceAttached = true; save(gift); return delay(undefined as void); }
    throw new ApiError('evidence_token_invalid', 'This upload is no longer available.', 400);
  },
  async status(trackingCode: string, _locale: Locale) {
    const gift = load()[trackingCode.replace(/[\s-]/g, '').toUpperCase()];
    if (!gift) throw new ApiError('donation_not_found', 'No gift was found for that code.', 404);
    const { trackingCode: _c, ...status } = gift;
    return delay(status);
  },
};
