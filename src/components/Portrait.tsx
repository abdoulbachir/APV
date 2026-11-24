import { fill, useI18n } from '../i18n/I18nContext';
import renewal from '../assets/hope-window.svg';
import home from '../assets/new-home.svg';
export function Portrait({ name, variant = 'large', photoUrl, code }: { name: string; variant?: 'large' | 'small'; photoUrl?: string | null; code?: string }) {
  const { t } = useI18n();
  return <div className={'portrait portrait-' + variant} role="img" aria-label={fill(photoUrl ? t.redesign.photoLabel : t.caseCard.noPhoto, { name })}>
    <img src={photoUrl || (code === 'HELP-014' && variant === 'small' ? home : renewal)} alt="" />
  </div>;
}
