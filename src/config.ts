export const SITE_NAME = 'APV-GAKASINAY';
const mode = import.meta.env.VITE_API_MODE ?? 'mock';
if (mode !== 'mock' && mode !== 'real') throw new Error('Invalid VITE_API_MODE');
export const APP_CONFIG = {
  apiMode: mode as 'mock' | 'real',
  sampleData: mode === 'mock' || import.meta.env.VITE_SAMPLE_DATA === 'true',
  apiBaseUrl: '/api',
  mockDelayMs: 540,
} as const;
