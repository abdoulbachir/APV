import { APP_CONFIG } from '../config';
import { imageSource } from '../api/media';
import { useState } from 'react';
import { fill, useI18n } from '../i18n/I18nContext';
import { artworkSource, type CaseArtwork } from '../assets/case-artwork';

export function Portrait({ name, variant = 'large', photoUrl, photoAlt, photoAltLanguage, artwork = 'renewal' }: { name: string; variant?: 'large' | 'small'; photoUrl?: string | null; photoAlt?: string | null; photoAltLanguage?: 'en'|'fr'|null; artwork?: CaseArtwork }) {
  const { t } = useI18n();
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const source = imageSource(photoUrl);
  const showPhoto = !!source && failedUrl !== photoUrl;
  const art = artworkSource(artwork);
  const description = showPhoto ? (photoAlt ?? fill(t.redesign.photoLabel, { name })) : (art.generated ? t.redesign.generatedIllustration + '. ' : '') + (artwork === 'home' ? t.redesign.homeArtworkAlt : t.redesign.renewalArtworkAlt);
  return <div className={'portrait portrait-' + variant}>
    <img loading={variant === 'small' ? 'lazy' : 'eager'} decoding="async" src={showPhoto ? source : art.url} alt={description} lang={showPhoto ? photoAltLanguage ?? undefined : undefined} onError={showPhoto ? () => setFailedUrl(photoUrl!) : undefined} />
    {(!showPhoto || APP_CONFIG.sampleData) && <span className="art-caption" aria-hidden="true">{art.generated ? t.redesign.generatedIllustration : t.redesign.illustration}</span>}
  </div>;
}
