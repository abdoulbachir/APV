import { fill, useI18n } from '../i18n/I18nContext';
import cinematicSupport from '../assets/cinematic-support.webp';

export function Portrait({ name, variant = 'large' }: { name: string; variant?: 'large' | 'small' }) {
  const { t } = useI18n();
  return (
    <div className={`portrait portrait-${variant}`} role="img" aria-label={fill(t.caseCard.noPhoto, { name })}>
      <img src={cinematicSupport} alt="" />
      <span className="portrait-atmosphere" aria-hidden="true" />
      <span className="portrait-index" aria-hidden="true">{name.slice(0, 1)}</span>
    </div>
  );
}
