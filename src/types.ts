export type Cents = number;
export type Role = 'user' | 'admin' | 'super_admin';
export type CaseStatus = 'draft' | 'active' | 'funded' | 'delivered' | 'closed';
export type Visibility = 'named' | 'anonymous' | 'hidden';
export type DonationStatus = 'reported' | 'confirmed' | 'rejected';
export type MethodType = 'zelle' | 'paypal' | 'other';

export interface User { id: string; name: string; email: string; role: Role; canDisburse: boolean; status: 'active' | 'disabled'; }

export interface Case {
  code: string;
  kind: 'case' | 'fund';
  title: string;
  publicName: string;
  story: string;
  photoUrl: string | null;
  goal: Cents | null;
  deadline: string | null;
  status: CaseStatus;
  received: Cents;
  delivered: Cents;
  createdAt: string;
}

export interface CasePrivate { realName: string; contact: string; notes: string; }
export interface PaymentMethod { id: string; type: MethodType; label: string; handle: string; instructions: string; active: boolean; }
export interface Donation { id: string; caseCode: string; amount: Cents; method: MethodType; donorName: string | null; visibility: Visibility; status: DonationStatus; screenshotUrl: string | null; createdAt: string; confirmedAt: string | null; }
export interface PublicDonor { name: string | null; amount: Cents; date: string; }
export interface Disbursement { id: string; caseCode: string; amount: Cents; date: string; method: string; note: string; proofUrl: string | null; }
export interface Transfer { id: string; fromCode: string; toCode: string; amount: Cents; reason: string; date: string; }
export interface CaseUpdate { id: string; caseCode: string; text: string; photoUrl: string | null; date: string; author: string; }
export interface LedgerEntry { kind: 'donation' | 'disbursement' | 'transfer_in' | 'transfer_out'; amount: Cents; date: string; label: string; }
export interface Stats { received: Cents; delivered: Cents; peopleHelped: number; }
export interface ContactMessage { id: string; name: string; contact: string; message: string; caseCode: string | null; createdAt: string; }
export interface AuditEntry { id: string; actor: string; action: string; target: string; at: string; }

export interface CaseDetail {
  case: Case;
  updates: CaseUpdate[];
  donors: PublicDonor[];
  ledger: LedgerEntry[];
}
