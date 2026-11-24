import { APP_CONFIG } from '../config';

export type CaseArtwork = 'renewal' | 'home';
// Optional art direction for mock fixtures only. Real cases use their public
// photo, if provided, or the neutral renewal illustration.
const sampleCardArtwork: Readonly<Record<string, CaseArtwork>> = { 'HELP-014': 'home' };
export function getCaseArtwork(code: string, variant: 'large' | 'small'): CaseArtwork {
  return APP_CONFIG.apiMode === 'mock' && variant === 'small' ? sampleCardArtwork[code] ?? 'renewal' : 'renewal';
}
