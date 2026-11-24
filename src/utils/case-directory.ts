import { APP_CONFIG } from '../config';
import { categories, fixtureCategory, type Category } from '../api/directory-fixtures';
import type { Case } from '../types';

export { categories };
export const PAGE_SIZE = 12;
export const sorts = ['newest', 'deadline', 'closest', 'least'] as const;
export type Sort = typeof sorts[number];
export function categoryFor(item: Case): Category | null {
  return APP_CONFIG.apiMode === 'mock' ? fixtureCategory(item.code) : null;
}
export function goalRatio(item: Case) {
  return item.goal !== null && item.goal > 0 ? Math.max(0, item.received / item.goal) : null;
}
export function normalize(value: string) {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}
export function browseCases(items: Case[], query: string, category: string, sort: Sort, label: (item: Case) => string) {
  const q = normalize(query);
  const rows = items.filter(item => item.kind === 'case' && (category === 'all' || categoryFor(item) === category)
    && normalize([item.title, item.publicName, item.code, label(item)].join(' ')).includes(q));
  const newest = (a: Case, b: Case) => b.createdAt.localeCompare(a.createdAt) || a.code.localeCompare(b.code);
  return rows.sort((a, b) => {
    if (sort === 'deadline') {
      if (a.deadline === null) return b.deadline === null ? newest(a, b) : 1;
      if (b.deadline === null) return -1;
      return a.deadline.localeCompare(b.deadline) || newest(a, b);
    }
    if (sort === 'closest' || sort === 'least') {
      const ar = goalRatio(a), br = goalRatio(b);
      if (ar === null) return br === null ? newest(a, b) : 1;
      if (br === null) return -1;
      return (sort === 'closest' ? br - ar : ar - br) || newest(a, b);
    }
    return newest(a, b);
  });
}
// Keep the existing featured case for the sample; real mode uses the newest
// published case until the backend supports editorial featured selection.
export function featuredCases(items: Case[]) {
  const sorted = browseCases(items, '', 'all', 'newest', () => '');
  const featured = APP_CONFIG.apiMode === 'mock' ? sorted.find(item => item.code === 'HELP-014') : sorted[0];
  return { featured, additional: sorted.filter(item => item.code !== featured?.code).slice(0, 3) };
}
