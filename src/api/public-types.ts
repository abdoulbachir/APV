// Application conveniences around the generated contract; no hand-written response DTOs.
import type { CasePage, PublicReadOperations } from './generated/public-api';
export type { Locale, Cents, PublicCaseStatus, LedgerKind, Category, Site, Stats, Case, CaseFinancials, CasePhoto, CaseUpdate, PublicDonor, LedgerEntry, CaseDetail } from './generated/public-api';
export type Page<T> = Omit<CasePage,'items'> & {items:T[]};
export type CaseQuery = PublicReadOperations['listCases']['query'];
export type PageQuery = PublicReadOperations['listCaseUpdates']['query'];
export type Sort = NonNullable<CaseQuery['sort']>;
