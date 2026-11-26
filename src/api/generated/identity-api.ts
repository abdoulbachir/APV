// Generated from contract/openapi.yaml (v0.3.0). Do not edit.
// Run: cd contract && npm run generate:types
// Private to the signed-in account. Never use these types in a public page's data.

import type { Locale, Timestamp } from './public-api';

export type AccountId = string;
export type EmailAddress = string;
export type DisplayName = string;
export type Role = "donor" | "admin" | "super_admin";
export type Permission = "account.read_own" | "account.change_password";
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
  getCsrfToken: { method: "GET"; request: null; response: CsrfToken; status: 200; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  getAuthOptions: { method: "GET"; request: null; response: AuthOptions; status: 200; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  register: { method: "POST"; request: RegisterRequest; response: RegistrationAccepted; status: 202; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  login: { method: "POST"; request: LoginRequest; response: LoginResult; status: 200; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  logout: { method: "POST"; request: null; response: null; status: 204; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required" | "authenticated"; };
  changePassword: { method: "POST"; request: ChangePasswordRequest; response: null; status: 204; allowedStates: "authenticated"; };
  setPasswordWithCredential: { method: "POST"; request: SetPasswordRequest; response: null; status: 204; allowedStates: "anonymous" | "mfa_required" | "mfa_enrollment_required"; };
  verifyMfa: { method: "POST"; request: MfaVerifyRequest; response: SignedIn; status: 200; allowedStates: "mfa_required"; };
  beginMfaEnrollment: { method: "POST"; request: null; response: MfaEnrollment; status: 200; allowedStates: "mfa_enrollment_required"; };
  confirmMfaEnrollment: { method: "POST"; request: MfaConfirmRequest; response: MfaRecoveryCodes; status: 200; allowedStates: "mfa_enrollment_required"; };
  regenerateRecoveryCodes: { method: "POST"; request: RecoveryCodesRequest; response: MfaRecoveryCodes; status: 200; allowedStates: "authenticated"; };
  getMyAccount: { method: "GET"; request: null; response: Account; status: 200; allowedStates: "authenticated"; };
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
