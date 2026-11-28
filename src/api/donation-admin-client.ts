// Client for the donation review operations of contract 0.4 (donation-lifecycle.md sections 6 to 8).
// Every operation needs a fully signed-in session holding donations.confirm; the server decides.
// A POST is sent once, with a token read just before it, and is never resent by this code: after
// 403 csrf_invalid or 401 the screen re-reads the session and the person decides what to do next.
import { ApiError } from './client';
import { donationPaths, type DonationOperations } from './generated/identity-api';
import type { Locale } from './public-types';

type Op = DonationOperations;
export type DonationPage = Op['listDonations']['response'];
export type DonationDetail = Op['getDonation']['response'];
export type DonationListQuery = Op['listDonations']['query'];
export type FieldProblem = { name: string; code: string };

export class DonationAdminError extends ApiError {
  constructor(code: string, message: string, status: number, public fields: FieldProblem[] = []) { super(code, message, status); }
}

const base = '/api';
const withId = (template: string, id: string) => template.replace('{donationId}', encodeURIComponent(id));
const common = (locale: Locale) => ({ Accept: 'application/json', 'Accept-Language': locale });

async function failure(response: Response): Promise<DonationAdminError> {
  const body = await response.json().catch(() => null);
  const fields = Array.isArray(body?.error?.fields) ? body.error.fields.filter((f: unknown): f is FieldProblem => !!f && typeof (f as FieldProblem).name === 'string') : [];
  return new DonationAdminError(body?.error?.code ?? 'unknown', body?.error?.message ?? response.statusText, response.status, fields);
}
async function send(input: string, init: RequestInit): Promise<Response> {
  try { return await fetch(input, { credentials: 'same-origin', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', ...init }); }
  catch { throw new DonationAdminError('network_error', 'Could not connect', 0); }
}
async function get<T>(path: string, locale: Locale): Promise<T> {
  const response = await send(base + path, { headers: common(locale) });
  if (!response.ok) throw await failure(response);
  return response.json() as Promise<T>;
}
async function csrf(locale: Locale): Promise<string> {
  const value = await get<{ token?: unknown }>('/auth/csrf', locale);
  if (typeof value.token !== 'string') throw new DonationAdminError('invalid_response', 'Unexpected response', 200);
  return value.token;
}
async function post<T>(path: string, body: unknown, locale: Locale, idempotencyKey?: string): Promise<T> {
  const token = await csrf(locale);
  const response = await send(base + path, {
    method: 'POST',
    headers: { ...common(locale), 'X-CSRF-TOKEN': token, ...(body === null ? {} : { 'Content-Type': 'application/json' }), ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) },
    ...(body === null ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) throw await failure(response);
  return response.json() as Promise<T>;
}

export const donationAdmin = {
  list(query: DonationListQuery, locale: Locale) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== '') params.set(key, String(value));
    return get<DonationPage>(donationPaths.listDonations + (params.size ? '?' + params : ''), locale);
  },
  get: (id: string, locale: Locale) => get<DonationDetail>(withId(donationPaths.getDonation, id), locale),
  evidenceUrl: (id: string) => base + withId(donationPaths.getDonationEvidence, id),
  enter: (body: Op['enterDonation']['request'], key: string, locale: Locale) => post<DonationDetail>(donationPaths.enterDonation, body, locale, key),
  confirm: (id: string, body: Op['confirmDonation']['request'], key: string, locale: Locale) => post<DonationDetail>(withId(donationPaths.confirmDonation, id), body, locale, key),
  reject: (id: string, body: Op['rejectDonation']['request'], locale: Locale) => post<DonationDetail>(withId(donationPaths.rejectDonation, id), body, locale),
  reopen: (id: string, locale: Locale) => post<DonationDetail>(withId(donationPaths.reopenDonation, id), null, locale),
  reverse: (id: string, body: Op['reverseDonation']['request'], key: string, locale: Locale) => post<DonationDetail>(withId(donationPaths.reverseDonation, id), body, locale, key),
  refund: (id: string, body: Op['refundDonation']['request'], key: string, locale: Locale) => post<DonationDetail>(withId(donationPaths.refundDonation, id), body, locale, key),
  restrictListing: (id: string, body: Op['restrictDonationListing']['request'], locale: Locale) => post<DonationDetail>(withId(donationPaths.restrictDonationListing, id), body, locale),
  deleteEvidence: (id: string, locale: Locale) => post<DonationDetail>(withId(donationPaths.deleteDonationEvidence, id), null, locale),
};
