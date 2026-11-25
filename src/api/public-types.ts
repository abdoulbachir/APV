// Public read DTOs from contract/openapi.yaml v0.2.0. Monetary values are integer USD cents.
export type Locale = 'en' | 'fr';
export type Cents = number;
export type PublicCaseStatus = 'active' | 'funded' | 'delivered' | 'closed';
export type LedgerKind = 'donation' | 'donation_reversal' | 'refund' | 'transfer_in' | 'transfer_out' | 'transfer_in_reversal' | 'transfer_out_reversal' | 'disbursement' | 'disbursement_reversal';
export interface Category { id: string; labels: Record<Locale,string>; position: number; active: boolean }
export interface Site { timezone: string; currency: 'USD'; locales: Locale[]; defaultLocale: Locale; categories: Category[]; fundCode: 'FUND'; pagination: { defaultPageSize: number; maxPageSize: number } }
export interface Stats { received: Cents; delivered: Cents; available: Cents; peopleHelped: number; asOf: string }
export interface Case {
  code: string; kind: 'case' | 'fund'; status: PublicCaseStatus; acceptingDonations: boolean;
  category: string | null; featuredRank: number | null; title: string; titleLanguage: Locale; publicName: string;
  story: string; storyLanguage: Locale; photoUrl: string | null; photoThumbnailUrl: string | null;
  photoAlt: string | null; photoAltLanguage: Locale | null; goal: Cents | null; received: Cents; delivered: Cents;
  deadline: string | null; deadlineTimezone: string | null; deadlineEndsAt: string | null; createdAt: string;
}
export interface CaseFinancials { externalDonations: Cents; transfersIn: Cents; transfersOut: Cents; allocated: Cents; delivered: Cents; available: Cents; hasShortfall: boolean }
export interface CasePhoto { id: string; url: string; thumbnailUrl: string; alt: string; altLanguage: Locale; position: number }
export interface CaseUpdate { id: string; caseCode: string; text: string; textLanguage: Locale; photoUrl: string | null; photoAlt: string | null; photoAltLanguage: Locale | null; date: string; author: string; authorAvatarUrl: string | null }
export interface PublicDonor { id: string; name: string | null; amount: Cents; date: string; avatarUrl: string | null }
export interface LedgerEntry { id: string; kind: LedgerKind; amount: Cents; allocatedDelta: Cents; deliveredDelta: Cents; date: string; label: string; corrects: string | null; correctedBy: string[]; correctedAmount: Cents; netAmount: Cents; counterpartCaseCode: string | null }
export interface CaseDetail { case: Case; financials: CaseFinancials; photos: CasePhoto[]; updates: CaseUpdate[]; donors: PublicDonor[]; ledger: LedgerEntry[]; counts: {updates:number; donors:number; ledger:number; photos:number}; asOf: string }
export interface Page<T> { items: T[]; page: number; pageSize: number; totalItems: number; totalPages: number }
export type Sort = 'newest' | 'deadline' | 'closest' | 'least' | 'featured';
export interface CaseQuery { status?: 'active' | 'completed'; q?: string; category?: string; sort?: Sort; page?: number; pageSize?: number }
export interface PageQuery { page?: number; pageSize?: number }
