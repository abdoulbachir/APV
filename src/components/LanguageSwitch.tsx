import { useI18n } from '../i18n/I18nContext';

export function LanguageSwitch() {
  const { locale, setLocale, t } = useI18n();
  return (
    <div className="language-switch" aria-label={t.nav.language} role="group">
      <button type="button" className={locale === 'en' ? 'is-active' : ''} onClick={() => setLocale('en')} aria-pressed={locale === 'en'}>{t.nav.english}</button>
      <button type="button" className={locale === 'fr' ? 'is-active' : ''} onClick={() => setLocale('fr')} aria-pressed={locale === 'fr'}>{t.nav.french}</button>
    </div>
  );
}
