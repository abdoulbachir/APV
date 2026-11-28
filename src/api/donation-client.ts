// Client for the giving flow of contract 0.4.0 (donation-lifecycle.md): read the active payment
// methods, report a gift, attach one screenshot, and look up one gift by its tracking code. The
// four operations take any session state and no account. Staff review operations are a later slice.
import { ApiError } from './client';
import { donationPaths, type DonationOperations } from './generated/identity-api';
import type { Locale } from './public-types';

type Report = DonationOperations['reportDonation']['request'];
type Receipt = DonationOperations['reportDonation']['response'];
type Methods = DonationOperations['listPaymentMethods']['response'];
type Status = DonationOperations['getDonationStatus']['response'];

export interface DonationClient {
  listMethods(locale: Locale): Promise<Methods>;
  /** The session's token, read when the report form opens (donation-lifecycle 3.1). */
  token(locale: Locale): Promise<string>;
  report(body: Report, idempotencyKey: string, token: string, locale: Locale): Promise<Receipt>;
  attachEvidence(token: string, file: Blob, locale: Locale): Promise<void>;
  status(trackingCode: string, locale: Locale): Promise<Status>;
}

const csrfHeader = 'X-CSRF-TOKEN';

/** A random key the page keeps with a form, so a resend or a lost answer never makes a second gift. */
export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export type FieldProblem = { name: string; code: string };
/** A refusal, with the fields the server named when it was a validation error. */
export class DonationError extends ApiError {
  constructor(code: string, message: string, status: number, public fields: FieldProblem[] = []) { super(code, message, status); }
}

async function fail(response: Response): Promise<DonationError> {
  const body = await response.json().catch(() => null);
  const fields = Array.isArray(body?.error?.fields) ? body.error.fields.filter((f: unknown): f is FieldProblem => !!f && typeof (f as FieldProblem).name === 'string') : [];
  return new DonationError(body?.error?.code ?? 'unknown', body?.error?.message ?? response.statusText, response.status, fields);
}

async function csrfToken(locale: Locale): Promise<string> {
  const response = await fetch('/api/auth/csrf', {
    credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', 'Accept-Language': locale },
  }).catch(() => { throw new ApiError('network_error', 'Could not connect', 0); });
  if (!response.ok) throw await fail(response);
  const value = await response.json().catch(() => null);
  if (!value || typeof value.token !== 'string') throw new ApiError('invalid_response', 'Unexpected response', response.status);
  return value.token as string;
}

const realClient: DonationClient = {
  token: csrfToken,
  async listMethods(locale) {
    const response = await fetch('/api' + donationPaths.listPaymentMethods, {
      credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', 'Accept-Language': locale },
    }).catch(() => { throw new ApiError('network_error', 'Could not connect', 0); });
    if (!response.ok) throw await fail(response);
    return response.json() as Promise<Methods>;
  },
  async report(body, idempotencyKey, token, locale) {
    const response = await fetch('/api' + donationPaths.reportDonation, {
      method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', 'Accept-Language': locale, 'Content-Type': 'application/json', [csrfHeader]: token, 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }).catch(() => { throw new ApiError('network_error', 'Could not connect', 0); });
    if (!response.ok) throw await fail(response);
    return response.json() as Promise<Receipt>;
  },
  async attachEvidence(evidenceToken, file, locale) {
    const token = await csrfToken(locale);
    const response = await fetch('/api' + donationPaths.attachDonationEvidence, {
      method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer',
      headers: { 'Accept-Language': locale, 'Content-Type': file.type, [csrfHeader]: token, 'X-Evidence-Token': evidenceToken },
      body: file,
    }).catch(() => { throw new ApiError('network_error', 'Could not connect', 0); });
    if (!response.ok) throw await fail(response);
  },
  async status(trackingCode, locale) {
    const token = await csrfToken(locale);
    const response = await fetch('/api' + donationPaths.getDonationStatus, {
      method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', 'Accept-Language': locale, 'Content-Type': 'application/json', [csrfHeader]: token },
      body: JSON.stringify({ trackingCode }),
    }).catch(() => { throw new ApiError('network_error', 'Could not connect', 0); });
    if (!response.ok) throw await fail(response);
    return response.json() as Promise<Status>;
  },
};

async function loadMock(): Promise<DonationClient> {
  return (await import('./donation-mock')).mockDonationClient;
}

// Compile-time mode check, so a real build drops the mock import entirely (as api does in client.ts).
export const donations: DonationClient = import.meta.env.VITE_API_MODE === 'mock' ? {
  listMethods: async l => (await loadMock()).listMethods(l),
  token: async l => (await loadMock()).token(l),
  report: async (b, k, t, l) => (await loadMock()).report(b, k, t, l),
  attachEvidence: async (t, f, l) => (await loadMock()).attachEvidence(t, f, l),
  status: async (c, l) => (await loadMock()).status(c, l),
} : realClient;
