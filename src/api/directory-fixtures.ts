import type { Case, CaseDetail } from '../types';
import type { Locale } from '../i18n/translations';

export const categories = ['education', 'medical', 'housing', 'family', 'emergency', 'other'] as const;
export type Category = typeof categories[number];
const stories = {
  en: [
    ['School supplies for a new term', 'Help cover school materials and essential learning expenses for a family preparing for the new term.'],
    ['Access to essential medical care', 'Help a household cover essential medical appointments and transportation.'],
    ['A safe place to call home', 'Help a family cover basic household essentials and housing costs.'],
    ['Helping a family get back on track', 'Help a household meet essential daily needs during a difficult period.'],
    ['Support after an unexpected crisis', 'Help cover urgent everyday essentials after an unexpected disruption.'],
    ['Building a better tomorrow', 'Help a household cover practical expenses as they work toward greater stability.'],
  ],
  fr: [
    ['Des fournitures pour la rentrée', 'Aider une famille à financer les fournitures et les dépenses scolaires essentielles.'],
    ['Accès aux soins essentiels', 'Aider un foyer à financer des consultations médicales essentielles et les déplacements.'],
    ['Un logement sûr', 'Aider une famille à financer les besoins du foyer et les frais de logement.'],
    ['Aider une famille à se relever', 'Aider un foyer à couvrir ses besoins quotidiens essentiels pendant une période difficile.'],
    ['Un soutien après une crise imprévue', 'Aider à couvrir les besoins essentiels urgents après un événement imprévu.'],
    ['Construire un avenir meilleur', 'Aider un foyer à financer ses dépenses pratiques pour retrouver une situation plus stable.'],
  ],
} as const;

// Only the mock adapter uses these generated records. No API schema changes.
export function fixtureCategory(code: string): Category | null {
  if (code === 'HELP-014') return 'housing';
  const number = /^HELP-(\d{3})$/.exec(code)?.[1];
  const i = Number(number) - 101;
  return i >= 0 && i < 199 ? categories[i % categories.length] : null;
}

export function directoryFixtures(locale: Locale): Case[] {
  const day = 86400000;
  const epoch = Date.UTC(2026, 10, 24, 12);
  return Array.from({ length: 199 }, (_, i) => {
    const goal = (2000 + (i % 11) * 650) * 100;
    const received = Math.floor(goal * ((i * 17) % 100) / 100);
    const delivered = i % 3 === 0 ? Math.floor(received / 2) : 0;
    const [title, story] = stories[locale][i % categories.length];
    const code = 'HELP-' + (101 + i);
    return {
      code, kind: 'case', status: 'active', title,
      publicName: (locale === 'fr' ? 'Foyer fictif ' : 'Sample household ') + (i + 1),
      story, photoUrl: null, goal, received, delivered,
      deadline: i % 9 === 0 ? null : new Date(epoch + (3 + (i * 7) % 60) * day).toISOString(),
      createdAt: new Date(epoch - (i + 1) * day).toISOString(),
    };
  });
}

export function fixtureDetail(item: Case, locale: Locale): CaseDetail {
  const date = item.createdAt;
  return {
    case: item, updates: [],
    donors: item.received > 0 ? [{ name: null, amount: item.received, date }] : [],
    ledger: [
      ...(item.received > 0 ? [{ kind: 'donation' as const, amount: item.received, date, label: locale === 'fr' ? 'Don fictif confirmé' : 'Sample confirmed gift' }] : []),
      ...(item.delivered > 0 ? [{ kind: 'disbursement' as const, amount: item.delivered, date, label: locale === 'fr' ? 'Remise fictive' : 'Sample disbursement' }] : []),
    ],
  };
}
