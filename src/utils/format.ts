import type { Cents } from '../types';
import type { Locale } from '../i18n/translations';

export function formatMoney(cents: Cents, locale: Locale, compact = false) {
  return new Intl.NumberFormat(locale === 'fr' ? 'fr-FR' : 'en-US', {
    style: 'currency', currency: 'USD', maximumFractionDigits: cents % 100 === 0 ? 0 : 2,
    notation: compact ? 'compact' : 'standard',
  }).format(cents / 100);
}

export function formatDate(iso: string, locale: Locale, long = false) {
  return new Intl.DateTimeFormat(locale === 'fr' ? 'fr-FR' : 'en-US', {
    month: long ? 'long' : 'short', day: 'numeric', year: long ? 'numeric' : undefined,
  }).format(new Date(iso));
}
