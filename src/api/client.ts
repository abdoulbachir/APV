import { APP_CONFIG } from '../config';
import { directoryFixtures, fixtureDetail } from './directory-fixtures';
import { mockContent, type Locale } from '../i18n/translations';
import type { Case, CaseDetail, Stats } from '../types';

export class ApiError extends Error {
  constructor(public code: string, message: string, public status: number) { super(message); }
}

export interface ApiClient {
  getStats(locale: Locale): Promise<Stats>;
  getCases(status: 'active' | 'completed', locale: Locale): Promise<Case[]>;
  getCase(code: string, locale: Locale): Promise<CaseDetail>;
}

async function request<T>(path: string, locale: Locale): Promise<T> {
  const response = await fetch(`${APP_CONFIG.apiBaseUrl}${path}`, {
    credentials: 'include', headers: { Accept: 'application/json', 'Accept-Language': locale },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: { code: 'unknown', message: response.statusText } }));
    throw new ApiError(body.error?.code ?? 'unknown', body.error?.message ?? response.statusText, response.status);
  }
  return response.json() as Promise<T>;
}

const realClient: ApiClient = {
  getStats: (locale) => request<Stats>('/stats', locale),
  getCases: (status, locale) => request<Case[]>(`/cases?status=${status}`, locale),
  getCase: (code, locale) => request<CaseDetail>(`/cases/${encodeURIComponent(code)}`, locale),
};

async function mockResult<T>(value: T): Promise<T> {
  await new Promise((resolve) => window.setTimeout(resolve, APP_CONFIG.mockDelayMs));
  if (import.meta.env.DEV && new URLSearchParams(window.location.search).get('mockError') === '1') {
    throw new ApiError('mock_network_error', 'Simulated connection error', 503);
  }
  return structuredClone(value);
}

const mockClient: ApiClient = {
  getStats: (locale) => {
    const stats = mockContent(locale).stats;
    const extra = directoryFixtures(locale);
    return mockResult({
      received: stats.received + extra.reduce((sum, item) => sum + item.received, 0),
      delivered: stats.delivered + extra.reduce((sum, item) => sum + item.delivered, 0),
      peopleHelped: stats.peopleHelped + extra.filter(item => item.delivered > 0).length,
    });
  },
  getCases: (status, locale) => {
    const { cases } = mockContent(locale);
    const result = status === 'active' ? [...cases, ...directoryFixtures(locale)] : [];
    return mockResult(result);
  },
  getCase: async (code, locale) => {
    const content = mockContent(locale);
    const found = content.cases.find((item) => item.code.toLowerCase() === code.toLowerCase() && item.kind === 'case');
    if (!found) {
      const fixture = directoryFixtures(locale).find(item => item.code.toLowerCase() === code.toLowerCase());
      if (fixture) return mockResult(fixtureDetail(fixture, locale));
      await mockResult(null);
      throw new ApiError('case_not_found', 'Case not found', 404);
    }
    return mockResult({ case: found, updates: content.updates, donors: content.donors, ledger: content.ledger });
  },
};

export const api: ApiClient = APP_CONFIG.apiMode === 'mock' ? mockClient : realClient;
