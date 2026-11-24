import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { translations, type Locale, type Translation } from './translations';

interface I18nValue { locale: Locale; setLocale: (locale: Locale) => void; t: Translation; }

const I18nContext = createContext<I18nValue | null>(null);
const STORAGE_KEY = 'apv-locale';

function initialLocale(): Locale {
  const requested = new URLSearchParams(window.location.search).get('lang');
  if (requested === 'fr' || requested === 'en') return requested;
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (stored === 'fr' || stored === 'en') return stored;
  return window.navigator.language.toLowerCase().startsWith('fr') ? 'fr' : 'en';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);
  const setLocale = (next: Locale) => { window.localStorage.setItem(STORAGE_KEY, next); setLocaleState(next); };
  useEffect(() => { document.documentElement.lang = locale; }, [locale]);
  const value = useMemo(() => ({ locale, setLocale, t: translations[locale] as Translation }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const context = useContext(I18nContext);
  if (!context) throw new Error('useI18n must be used inside I18nProvider');
  return context;
}

export function fill(template: string, values: Record<string, string>) {
  return Object.entries(values).reduce((result, [key, value]) => result.replace(`{${key}}`, value), template);
}
