// Generated from contract/openapi.yaml (v0.2.0). Do not edit.
// Run: cd contract && npm run generate:types
// Runtime constraints (patterns, numeric bounds, etc.) are enforced by contract tests.

export type MovementCents = number;
export type Cents = number;
export type SignedCents = number;
export type Timestamp = string;
export type Locale = "en" | "fr";
export type MediaPath = string;
export type CategoryId = string;
export type Category = { "id": CategoryId; "labels": { "en": string; "fr": string; }; "position": number; "active": boolean; };
export type CaseKind = "case" | "fund";
export type PublicCaseStatus = "active" | "funded" | "delivered" | "closed";
export type ErrorCode = "validation_error" | "case_not_found" | "not_found" | "rate_limited" | "internal_error" | "unavailable";
export type Error = { "error": { "code": ErrorCode; "message": string; "fields"?: Array<{ "name": string; "code": "required" | "invalid_type" | "invalid_value" | "out_of_range" | "too_long"; }>; "requestId"?: string; }; };
export type Site = { "timezone": string; "currency": "USD"; "locales": Array<Locale>; "defaultLocale": Locale; "categories": Array<Category>; "fundCode": "FUND"; "pagination": { "defaultPageSize": number; "maxPageSize": number; }; };
export type Stats = { "received": Cents; "delivered": Cents; "available": SignedCents; "peopleHelped": number; "asOf": Timestamp; };
export type Case = { "code": string; "kind": CaseKind; "status": PublicCaseStatus; "acceptingDonations": boolean; "category": CategoryId | null; "featuredRank": number | null; "title": string; "titleLanguage": Locale; "publicName": string; "story": string; "storyLanguage": Locale; "photoUrl": MediaPath | null; "photoThumbnailUrl": MediaPath | null; "photoAlt": string | null; "photoAltLanguage": Locale | null; "goal": MovementCents | null; "received": SignedCents; "delivered": Cents; "deadline": string | null; "deadlineTimezone": string | null; "deadlineEndsAt": Timestamp | null; "createdAt": Timestamp; };
export type CaseFinancials = { "externalDonations": Cents; "transfersIn": Cents; "transfersOut": Cents; "allocated": SignedCents; "delivered": Cents; "available": SignedCents; "hasShortfall": boolean; };
export type CasePhoto = { "id": string; "url": MediaPath; "thumbnailUrl": MediaPath; "alt": string; "altLanguage": Locale; "position": number; };
export type CaseUpdate = { "id": string; "caseCode": string; "text": string; "textLanguage": Locale; "photoUrl": MediaPath | null; "photoAlt": string | null; "photoAltLanguage": Locale | null; "date": Timestamp; "author": string; "authorAvatarUrl": MediaPath | null; };
export type PublicDonor = { "id": string; "name": string | null; "amount": MovementCents; "date": Timestamp; "avatarUrl": MediaPath | null; };
export type LedgerKind = "donation" | "donation_reversal" | "refund" | "transfer_in" | "transfer_out" | "transfer_in_reversal" | "transfer_out_reversal" | "disbursement" | "disbursement_reversal";
export type LedgerEntry = { "id": string; "kind": LedgerKind; "amount": MovementCents; "allocatedDelta": SignedCents; "deliveredDelta": SignedCents; "date": Timestamp; "label": string; "corrects": string | null; "correctedBy": Array<string>; "correctedAmount": Cents; "netAmount": Cents; "counterpartCaseCode": string | null; };
export type HistoryCounts = { "updates": number; "donors": number; "ledger": number; "photos": number; };
export type CaseDetail = { "case": Case; "financials": CaseFinancials; "photos": Array<CasePhoto>; "updates": Array<CaseUpdate>; "donors": Array<PublicDonor>; "ledger": Array<LedgerEntry>; "counts": HistoryCounts; "asOf": Timestamp; };
export type CasePage = { "items": Array<Case>; "page": PageNumber; "pageSize": PageSizeValue; "totalItems": TotalItems; "totalPages": TotalPages; };
export type CaseUpdatePage = { "items": Array<CaseUpdate>; "page": PageNumber; "pageSize": PageSizeValue; "totalItems": TotalItems; "totalPages": TotalPages; };
export type PublicDonorPage = { "items": Array<PublicDonor>; "page": PageNumber; "pageSize": PageSizeValue; "totalItems": TotalItems; "totalPages": TotalPages; };
export type LedgerEntryPage = { "items": Array<LedgerEntry>; "page": PageNumber; "pageSize": PageSizeValue; "totalItems": TotalItems; "totalPages": TotalPages; };
export type PageNumber = number;
export type PageSizeValue = number;
export type TotalItems = number;
export type TotalPages = number;

export interface PublicReadOperations {
  getSite: { response: Site; query: {  }; path: {  }; };
  getStats: { response: Stats; query: {  }; path: {  }; };
  listCases: { response: CasePage; query: { "status"?: "active" | "completed"; "q"?: string; "category"?: CategoryId; "sort"?: "newest" | "deadline" | "closest" | "least" | "featured"; "page"?: number; "pageSize"?: number; }; path: {  }; };
  getCase: { response: CaseDetail; query: {  }; path: { "code": string; }; };
  listCaseUpdates: { response: CaseUpdatePage; query: { "page"?: number; "pageSize"?: number; }; path: { "code": string; }; };
  listCaseDonors: { response: PublicDonorPage; query: { "page"?: number; "pageSize"?: number; }; path: { "code": string; }; };
  listCaseLedger: { response: LedgerEntryPage; query: { "page"?: number; "pageSize"?: number; }; path: { "code": string; }; };
}

export const publicReadPaths = {
  getSite: "/site",
  getStats: "/stats",
  listCases: "/cases",
  getCase: "/cases/{code}",
  listCaseUpdates: "/cases/{code}/updates",
  listCaseDonors: "/cases/{code}/donors",
  listCaseLedger: "/cases/{code}/ledger",
} as const;
