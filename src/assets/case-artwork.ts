import renewalFallback from './hope-window.svg';
import homeFallback from './new-home.svg';

export type CaseArtwork = 'renewal' | 'home';

// Optional local photographic assets. Keep the symbolic vector fallbacks so the
// sample remains buildable while its generated imagery is being added.
const images = import.meta.glob<string>('./*.webp', { eager: true, query: '?url', import: 'default' });
const photograph = {
  renewal: images['./hope-window.webp'],
  home: images['./new-home.webp'],
};

export function artworkSource(artwork: CaseArtwork) {
  const generated = photograph[artwork];
  return {
    url: generated || (artwork === 'home' ? homeFallback : renewalFallback),
    generated: !!generated,
  };
}

// Fixture-specific imagery is separate from the UI and applies only in mock mode.
const sampleArtwork: Readonly<Record<string, CaseArtwork>> = { 'HELP-014': 'home' };
export function getCaseArtwork(code: string): CaseArtwork {
  return import.meta.env.VITE_API_MODE === 'mock' ? sampleArtwork[code] ?? 'renewal' : 'renewal';
}
