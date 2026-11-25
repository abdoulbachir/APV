import type { Case, Sort } from '../api/public-types';
export type { Sort } from '../api/public-types';
export const PAGE_SIZE = 12;
export const sorts = ['newest','deadline','closest','least','featured'] as const;
const styles = new Set(['education','medical','housing','family','emergency','other']);
export const categoryFor = (item: Case) => item.category;
export const categoryStyle = (id: string | null) => id && styles.has(id) ? id : 'other';
export function goalRatio(item: Case) { return item.goal === null ? null : Math.max(0,item.received/item.goal); }
export function searchValid(value: string) { return [...value].length <= 200 && [...value.trim()].length <= 80; }
