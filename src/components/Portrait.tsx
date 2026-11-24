import { useState } from 'react';
import { fill, useI18n } from '../i18n/I18nContext';
import type { CaseArtwork } from '../assets/case-artwork';
import renewal from '../assets/hope-window.svg';
import home from '../assets/new-home.svg';

export function Portrait({ name, variant = 'large', photoUrl, artwork = 'renewal' }: { name: string; variant?: 'large' | 'small'; photoUrl?: string | null; artwork?: CaseArtwork }) {
  const { t } = useI18n();
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const showPhoto = !!photoUrl && failedUrl !== photoUrl;
  const description = showPhoto ? fill(t.redesign.photoLabel, { name }) : artwork === 'home' ? t.redesign.homeArtworkAlt : t.redesign.renewalArtworkAlt;
  return <div className={'portrait portrait-' + variant}>
    <img src={showPhoto ? photoUrl! : artwork === 'home' ? home : renewal} alt={description} onError={showPhoto ? () => setFailedUrl(photoUrl!) : undefined} />
    {!showPhoto && <span className="art-caption">{t.redesign.illustration}</span>}
  </div>;
}
