// Generated from contract/openapi.yaml (v0.4.2). Do not edit.
// Run: cd contract && npm run generate:types
// Private to the signed-in account. Never use these types in a public page's data.

import type { Cents, DonationAmount, DonationReference, DonationState, Locale, PageNumber, PageSizeValue, PaidDate, PaymentDetailsId, PaymentMethodId, PaymentMethodList, RecipientField, Timestamp, TotalItems, TotalPages } from './public-api';

export type DonationId = string;
export type PayerName = string;
export type PublicDonorName = string;
export type DonationDisplay = { "mode": "named" | "anonymous" | "hidden"; "publicName"?: PublicDonorName; };
export type DonationReportRequest = { "caseCode": string; "amount": DonationAmount; "paymentDetailsId": PaymentDetailsId; "paidOn": PaidDate; "payerName": PayerName; "paymentReference"?: string; "contact"?: string; "display": DonationDisplay; };
export type EvidenceUpload = { "token": string; "expiresAt": Timestamp; "maxBytes": 5242880; "formats": Array<"image/jpeg" | "image/png">; };
export type DonationReceipt = { "reference": DonationReference; "trackingCode": string; "state": DonationState; "caseCode": string; "amount": DonationAmount; "reportedAt": Timestamp; "evidenceUpload": EvidenceUpload | null; };
export type DonationStatusRequest = { "trackingCode": string; };
export type DonationStatus = { "reference": DonationReference; "state": DonationState; "caseCode": string; "reportedAmount": DonationAmount; "reportedAt": Timestamp; "decidedAt": Timestamp | null; "confirmedAmount": DonationAmount | null; "confirmedCaseCode": string | null; "netAmount": Cents | null; "rejectionReason": "not_received" | "duplicate" | "other" | null; "evidenceAttached": boolean; };
export type DonationConfirmRequest = { "caseCode": string; "confirmedAmount": DonationAmount; "receivedOn": PaidDate; "providerReference"?: string; "listing": "as_requested" | "anonymous" | "hidden"; "note"?: string; };
export type DonationRejectRequest = { "reason": "not_received" | "duplicate" | "other"; "note"?: string; };
export type DonationEntryRequest = { "caseCode": string; "amount": DonationAmount; "receivedOn": PaidDate; "providerReference"?: string; "method": { "id"?: PaymentMethodId; "description"?: string; }; "payerName": PayerName; "display": DonationDisplay; "note": string; "reentryOf"?: DonationId; };
export type DonationReversalRequest = { "amount": DonationAmount; "reason": "duplicate_confirmation" | "wrong_amount" | "wrong_case" | "not_received" | "other"; "note": string; };
export type DonationRefundRequest = { "amount": DonationAmount; "refundedOn": PaidDate; "providerReference"?: string; "note": string; };
export type DonationListingRequest = { "listing": "anonymous" | "hidden"; };
export type StaffActor = { "id": AccountId; "displayName": DisplayName; };
export type ShownDetails = { "detailsId": PaymentDetailsId; "revision": number; "current": boolean; "supersededAt": Timestamp | null; "recipient": Array<RecipientField>; };
export type DonationMethodRef = { "id": PaymentMethodId | null; "description": string | null; };
export type DonationReported = { "caseCode": string; "amount": DonationAmount; "paidOn": PaidDate; "at": Timestamp; };
export type DonationDecision = { "state": "confirmed" | "rejected"; "at": Timestamp; "by": StaffActor; "caseCode": string | null; "amount": DonationAmount | null; "receivedOn": PaidDate | null; "providerReference": string | null; "listing": "named" | "anonymous" | "hidden" | null; "rejectionReason": "not_received" | "duplicate" | "other" | null; "note": string | null; };
export type DonationCorrection = { "ledgerEntryId": string; "kind": "donation_reversal" | "refund"; "amount": DonationAmount; "at": Timestamp; "reason": string | null; "providerReference": string | null; };
export type DonationHistoryEntry = { "at": Timestamp; "action": "reported" | "evidence_attached" | "evidence_deleted" | "confirmed" | "rejected" | "reopened" | "entered" | "reversed" | "refunded" | "listing_restricted"; "by": StaffActor | null; };
export type DonationDuplicateHint = { "id": DonationId; "reference": DonationReference; "state": DonationState; "amount": DonationAmount; "reportedAt": Timestamp; };
export type DonationListItem = { "id": DonationId; "reference": DonationReference; "origin": "reported" | "staff_entry"; "state": DonationState; "caseCode": string; "reportedAmount": DonationAmount | null; "confirmedAmount": DonationAmount | null; "netAmount": Cents | null; "methodId": PaymentMethodId | null; "payerName": string | null; "reportedAt": Timestamp; "decidedAt": Timestamp | null; "evidence": "none" | "attached" | "deleted"; "flags": Array<"superseded_details" | "possible_duplicate" | "shortfall" | "reentered" | "different_case">; };
export type DonationPage = { "items": Array<DonationListItem>; "page": PageNumber; "pageSize": PageSizeValue; "totalItems": TotalItems; "totalPages": TotalPages; "pendingCount": number; };
export type DonationDetail = { "id": DonationId; "reference": DonationReference; "origin": "reported" | "staff_entry"; "state": DonationState; "reported": DonationReported | null; "method": DonationMethodRef; "shownDetails": ShownDetails | null; "payerName": string | null; "paymentReference": string | null; "contact": string | null; "display": DonationDisplayState; "linkedAccount": boolean; "evidence": "none" | "attached" | "deleted"; "flags": Array<"superseded_details" | "possible_duplicate" | "shortfall" | "reentered" | "different_case">; "decision": DonationDecision | null; "ledgerEntryId": string | null; "netAmount": Cents | null; "corrections": Array<DonationCorrection>; "reentryOf": DonationId | null; "reenteredAs": DonationId | null; "possibleDuplicates": Array<DonationDuplicateHint>; "history": Array<DonationHistoryEntry>; "privateDetailsErasedAt": Timestamp | null; };
export type DonationDisplayState = { "mode": "named" | "anonymous" | "hidden"; "publicName": string | null; };
export type AccountId = string;
export type EmailAddress = string;
export type DisplayName = string;
export type Role = "donor" | "admin" | "super_admin";
export type Permission = "account.read_own" | "account.change_password" | "donations.confirm";
export type NewPassword = string;
export type ExistingPassword = string;
export type TotpCode = string;
export type OneTimeCredential = string;
export type RecoveryCode = string;
export type RegisterRequest = { "email": EmailAddress; "password": NewPassword; "displayName": DisplayName; "locale"?: Locale; };
export type LoginRequest = { "email": EmailAddress; "password": ExistingPassword; };
export type ChangePasswordRequest = { "currentPassword": ExistingPassword; "newPassword": NewPassword; };
export type SetPasswordRequest = { "credential": OneTimeCredential; "newPassword": NewPassword; };
export type MfaVerifyRequest = TotpVerification | RecoveryCodeVerification;
export type TotpVerification = { "method": "totp"; "code": TotpCode; };
export type RecoveryCodeVerification = { "method": "recovery_code"; "code": RecoveryCode; };
export type MfaConfirmRequest = { "code": TotpCode; };
export type RecoveryCodesRequest = { "currentPassword": ExistingPassword; "code": TotpCode; };
export type RegistrationAccepted = { "status": "accepted"; };
export type Account = { "id": AccountId; "email": EmailAddress; "emailVerified": boolean; "displayName": DisplayName; "role": Role; "permissions": Array<Permission>; "mfa": { "required": boolean; "enrolled": boolean; "recoveryCodesRemaining": number | null; }; "locale": Locale; "createdAt": Timestamp; "session": { "idleExpiresAt": Timestamp; "absoluteExpiresAt": Timestamp; }; };
export type LoginResult = SignedIn | SecondFactorPending;
export type SignedIn = { "status": "authenticated"; "account": Account; };
export type SecondFactorPending = { "status": "mfa_required" | "mfa_enrollment_required"; "pendingExpiresAt": Timestamp; };
export type AuthOptions = { "registrationOpen": boolean; };
export type CsrfToken = { "headerName": "X-CSRF-TOKEN"; "token": string; };
export type MfaEnrollment = { "secret": string; "otpauthUri": string; "expiresAt": Timestamp; };
export type MfaRecoveryCodes = { "codes": Array<string>; "generatedAt": Timestamp; };

/** Send this header, with the token from getCsrfToken, on methods other than GET, HEAD and OPTIONS. */
export const csrfHeaderName = "X-CSRF-TOKEN" as const;

/** The state of this browser's session. Each operation lists the states in which it may be called. */
export type SessionState = "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated";

export interface IdentityOperations {
  getCsrfToken: { method: "GET"; request: null; response: CsrfToken; status: 200; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  getAuthOptions: { method: "GET"; request: null; response: AuthOptions; status: 200; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  register: { method: "POST"; request: RegisterRequest; response: RegistrationAccepted; status: 202; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  login: { method: "POST"; request: LoginRequest; response: LoginResult; status: 200; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  logout: { method: "POST"; request: null; response: null; status: 204; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  changePassword: { method: "POST"; request: ChangePasswordRequest; response: null; status: 204; query: {  }; path: {  }; allowedStates: "authenticated"; };
  setPasswordWithCredential: { method: "POST"; request: SetPasswordRequest; response: null; status: 204; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  verifyMfa: { method: "POST"; request: MfaVerifyRequest; response: SignedIn; status: 200; query: {  }; path: {  }; allowedStates: "mfa_required"; };
  beginMfaEnrollment: { method: "POST"; request: null; response: MfaEnrollment; status: 200; query: {  }; path: {  }; allowedStates: "mfa_enrollment_required"; };
  confirmMfaEnrollment: { method: "POST"; request: MfaConfirmRequest; response: MfaRecoveryCodes; status: 200; query: {  }; path: {  }; allowedStates: "mfa_enrollment_required"; };
  regenerateRecoveryCodes: { method: "POST"; request: RecoveryCodesRequest; response: MfaRecoveryCodes; status: 200; query: {  }; path: {  }; allowedStates: "authenticated"; };
  getMyAccount: { method: "GET"; request: null; response: Account; status: 200; query: {  }; path: {  }; allowedStates: "authenticated"; };
}

export const identityPaths = {
  getCsrfToken: "/auth/csrf",
  getAuthOptions: "/auth/options",
  register: "/auth/register",
  login: "/auth/login",
  logout: "/auth/logout",
  changePassword: "/auth/change-password",
  setPasswordWithCredential: "/auth/set-password",
  verifyMfa: "/auth/mfa/verify",
  beginMfaEnrollment: "/auth/mfa/enroll",
  confirmMfaEnrollment: "/auth/mfa/confirm",
  regenerateRecoveryCodes: "/auth/mfa/recovery-codes",
  getMyAccount: "/me",
} as const;

/** Donation lifecycle operations (contract/donation-lifecycle.md). Reporting needs no account; review needs donations.confirm. */
export interface DonationOperations {
  listPaymentMethods: { method: "GET"; request: null; response: PaymentMethodList; status: 200; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  reportDonation: { method: "POST"; request: DonationReportRequest; response: DonationReceipt; status: 201; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  attachDonationEvidence: { method: "POST"; request: null; response: null; status: 204; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  getDonationStatus: { method: "POST"; request: DonationStatusRequest; response: DonationStatus; status: 200; query: {  }; path: {  }; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  listDonations: { method: "GET"; request: null; response: DonationPage; status: 200; query: { "status"?: "reported" | "confirmed" | "rejected" | "all"; "caseCode"?: string; "reference"?: string; "page"?: number; "pageSize"?: number; }; path: {  }; allowedStates: "authenticated"; };
  enterDonation: { method: "POST"; request: DonationEntryRequest; response: DonationDetail; status: 201; query: {  }; path: {  }; allowedStates: "authenticated"; };
  getDonation: { method: "GET"; request: null; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  getDonationEvidence: { method: "GET"; request: null; response: null; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  deleteDonationEvidence: { method: "POST"; request: null; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  confirmDonation: { method: "POST"; request: DonationConfirmRequest; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  rejectDonation: { method: "POST"; request: DonationRejectRequest; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  reopenDonation: { method: "POST"; request: null; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  reverseDonation: { method: "POST"; request: DonationReversalRequest; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  refundDonation: { method: "POST"; request: DonationRefundRequest; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
  restrictDonationListing: { method: "POST"; request: DonationListingRequest; response: DonationDetail; status: 200; query: {  }; path: { "donationId": DonationId; }; allowedStates: "authenticated"; };
}

export const donationPaths = {
  listPaymentMethods: "/giving/methods",
  reportDonation: "/donations",
  attachDonationEvidence: "/donations/evidence",
  getDonationStatus: "/donations/status",
  listDonations: "/admin/donations",
  enterDonation: "/admin/donations",
  getDonation: "/admin/donations/{donationId}",
  getDonationEvidence: "/admin/donations/{donationId}/evidence",
  deleteDonationEvidence: "/admin/donations/{donationId}/evidence/delete",
  confirmDonation: "/admin/donations/{donationId}/confirm",
  rejectDonation: "/admin/donations/{donationId}/reject",
  reopenDonation: "/admin/donations/{donationId}/reopen",
  reverseDonation: "/admin/donations/{donationId}/reversals",
  refundDonation: "/admin/donations/{donationId}/refunds",
  restrictDonationListing: "/admin/donations/{donationId}/listing",
} as const;
