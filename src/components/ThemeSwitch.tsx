import { useState } from 'react';
import { useI18n } from '../i18n/I18nContext';

type Theme = 'light' | 'dark';
const STORAGE_KEY = 'apv-theme';

export function ThemeSwitch() {
  const { t } = useI18n();
  const [theme, setTheme] = useState<Theme>(() =>
    document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
  );
  const dark = theme === 'dark';

  function toggle() {
    const next: Theme = dark ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    setTheme(next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch { /* Theme still works for this session. */ }
  }

  return (
    <button className="theme-switch" type="button" onClick={toggle}
      aria-label={t.theme.darkMode} aria-pressed={dark}
      title={dark ? t.theme.switchLight : t.theme.switchDark}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        {dark ? <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42" /></> : <path d="M20.8 14.1A9 9 0 0 1 9.9 3.2 9 9 0 1 0 20.8 14.1Z" />}
      </svg>
    </button>
  );
}
