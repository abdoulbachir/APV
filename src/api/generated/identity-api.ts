// Generated from contract/openapi.yaml (v0.3.0). Do not edit.
// Run: cd contract && npm run generate:types
// Private to the signed-in account. Never use these types in a public page's data.

import type { Locale, Timestamp } from './public-api';

export type AccountId = string;
export type EmailAddress = string;
export type DisplayName = string;
export type Role = "donor" | "admin" | "super_admin";
export type Permission = "account.read_own" | "account.change_password" | "donations.read_own" | "cases.manage" | "case_updates.post" | "donation_reports.read" | "disbursements.record" | "donations.confirm" | "funds.transfer" | "payment_methods.manage" | "staff.manage" | "contact_messages.read" | "audit.read";
export type NewPassword = string;
export type ExistingPassword = string;
export type TotpCode = string;
export type OneTimeCredential = string;
export type RegisterRequest = { "email": EmailAddress; "password": NewPassword; "displayName": DisplayName; "locale"?: Locale; };
export type LoginRequest = { "email": EmailAddress; "password": ExistingPassword; };
export type ChangePasswordRequest = { "currentPassword": ExistingPassword; "newPassword": NewPassword; };
export type SetPasswordRequest = { "credential": OneTimeCredential; "newPassword": NewPassword; };
export type MfaVerifyRequest = { "method": "totp" | "recovery_code"; "code": string; };
export type MfaConfirmRequest = { "code": TotpCode; };
export type RecoveryCodesRequest = { "currentPassword": ExistingPassword; "code": TotpCode; };
export type RegistrationAccepted = { "status": "accepted"; };
export type Account = { "id": AccountId; "email": EmailAddress; "emailVerified": boolean; "displayName": DisplayName; "role": Role; "permissions": Array<Permission>; "mfa": { "required": boolean; "enrolled": boolean; "recoveryCodesRemaining": number | null; }; "locale": Locale; "createdAt": Timestamp; "session": { "idleExpiresAt": Timestamp; "absoluteExpiresAt": Timestamp; }; };
export type LoginResult = { "status": "authenticated" | "mfa_required" | "mfa_enrollment_required"; "account": Account | null; };
export type CsrfToken = { "headerName": "X-XSRF-TOKEN"; "token": string; };
export type MfaEnrollment = { "secret": string; "otpauthUri": string; "expiresAt": Timestamp; };
export type MfaRecoveryCodes = { "codes": Array<string>; "generatedAt": Timestamp; };

/** Send this header, with the token from getCsrfToken, on every request that is not GET. */
export const csrfHeaderName = "X-XSRF-TOKEN" as const;

export interface IdentityOperations {
  getCsrfToken: { method: "GET"; request: null; response: CsrfToken; status: 200; needsSession: false; };
  register: { method: "POST"; request: RegisterRequest; response: RegistrationAccepted; status: 202; needsSession: false; };
  login: { method: "POST"; request: LoginRequest; response: LoginResult; status: 200; needsSession: false; };
  logout: { method: "POST"; request: null; response: null; status: 204; needsSession: false; };
  changePassword: { method: "POST"; request: ChangePasswordRequest; response: null; status: 204; needsSession: true; };
  setPasswordWithCredential: { method: "POST"; request: SetPasswordRequest; response: null; status: 204; needsSession: false; };
  verifyMfa: { method: "POST"; request: MfaVerifyRequest; response: LoginResult; status: 200; needsSession: true; };
  beginMfaEnrollment: { method: "POST"; request: null; response: MfaEnrollment; status: 200; needsSession: true; };
  confirmMfaEnrollment: { method: "POST"; request: MfaConfirmRequest; response: MfaRecoveryCodes; status: 200; needsSession: true; };
  regenerateRecoveryCodes: { method: "POST"; request: RecoveryCodesRequest; response: MfaRecoveryCodes; status: 200; needsSession: true; };
  getMyAccount: { method: "GET"; request: null; response: Account; status: 200; needsSession: true; };
}

export const identityPaths = {
  getCsrfToken: "/auth/csrf",
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
