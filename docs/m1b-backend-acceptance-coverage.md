# Milestone 1B backend: coverage of the security acceptance scenarios

Which executable test covers each of the 220 scenarios of `contract/tests/security-acceptance.md`, as of the commit that contains this file (after the corrective review, the final targeted corrections and the corrections that followed the first real-browser run). Generated from the test method names: a method named `a6_7_...` covers scenario 6.7.

This is the backend's part only. **It is not a statement that milestone 1B is accepted.** Scenarios that need the real frontend in a real browser are reported separately by the joint integration (a first run on 2026-11-25, to be repeated on this commit), and nothing here has been verified independently.

How the backend tests run: the whole application on a real port, real HTTP with one cookie jar per simulated browser, PostgreSQL 18 started by Testcontainers. On the backend developer's machine Docker was not available, so the PostgreSQL tests ran in GitHub Actions only ("Backend checks" workflow); unit tests ran in both places.

| Status | Scenarios | Meaning |
|---|---|---|
| Backend: covered | 175 | An API, database, concurrency or unit test asserts the expected result |
| Backend: covered. Browser repeat: joint integration | 10 | The matrix marks it **B**: the server behavior is asserted here, the same journey through the real frontend is still to do |
| Browser only: joint integration | 32 | Nothing the backend alone can show |
| Partly covered | 3 | The note says exactly what is and is not asserted |
| Not automated | 0 | No executable test; the note says what stands in its place |
| **Total** | **220** | |

Test classes are in `backend/src/test/java/org/apvgakasinay/identity/` (`*IT` need PostgreSQL; `internal/*Test` do not).

## 1. Sign-up and sign-in

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 1.1 | Sign up with a new address, then sign in | Backend: covered. Browser repeat: joint integration | `SignUpSignInIT.a1_1_signUpThenSignIn` |
| 1.2 | Sign up again with the same address and a different password | Backend: covered | `SignUpSignInIT.a1_2_and_1_8_signUpForAnExistingAddressGivesTheSameAnswerAndChangesNothing` |
| 1.3 | Sign up with the same address in different letter case | Backend: covered | `SignUpSignInIT.a1_3_and_1_9_lettersCaseDoesNotMakeASecondAccount` |
| 1.4 | An address with a leading or trailing space or tab, at sign-up and at sign-in | Backend: covered | `SignUpSignInIT.a1_4_theServerDoesNotTrimAnAddress` |
| 1.5 | Twenty simultaneous sign-ups for one address, each from a different client address so the per-address limit is not what is measured | Backend: covered | `SignUpSignInIT.a1_5_twentySimultaneousSignUpsForOneAddressMakeOneAccount` |
| 1.6 | Sign in with a wrong password, an unknown address, a disabled account and a not-activated staff account | Backend: covered. Browser repeat: joint integration | `SignUpSignInIT.a1_6_everyKindOfRefusedSignInLooksTheSame` |
| 1.7 | Timing of 1.6 over many runs | Backend: covered | `SignInTimingIT.a1_7_anUnknownAddressIsNotAnsweredFasterThanAWrongPassword` Sixty interleaved wrong-password sign-ins each for existing accounts and unknown addresses, compared by median; the ratio must stay within 0.80 to 1.25. The measured medians are published by the CI run (evidence sign-in-timing). A median comparison on a shared CI machine shows the absence of a gross difference, not of a small one. |
| 1.8 | Sign-up answer for a new and an existing address | Backend: covered | `SignUpSignInIT.a1_2_and_1_8_signUpForAnExistingAddressGivesTheSameAnswerAndChangesNothing` |
| 1.9 | Sign in with an address in different case | Backend: covered | `SignUpSignInIT.a1_3_and_1_9_lettersCaseDoesNotMakeASecondAccount` |
| 1.10 | Sign-up closed: `GET /auth/options`, then `POST /auth/register` | Backend: covered. Browser repeat: joint integration | `RealDatabaseIT.a1_10_a3_9_productionHasClosedSignUpAndTheSecureHostCookie` `RegistrationClosedIT.a1_10_closedSignUpIsAnnouncedAndRefused` |
| 1.11 | Start in the production profile with sign-up open and no address proof available | Backend: covered | `IdentitySecurityUnitTest.a3_10_and_1_11_productionRefusesToStartWithDevelopmentConveniences` |
| 1.12 | A preclaimed account: sign up under an address, then check everything keyed on that address | Backend: covered | `SignUpSignInIT.a1_12_anUnverifiedAddressGrantsNothing` |

## 2. Passwords

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 2.1 | 14 code points; 15; 128; 129 | Backend: covered | `SignUpSignInIT.a2_1_passwordLengthBoundsOverTheApi` `PasswordPolicyTest.acceptance_2_1_lengthBounds` |
| 2.2 | 15 emoji (30 UTF-16 units) | Backend: covered | `PasswordPolicyTest.acceptance_2_2_lengthIsCountedInCodePoints` |
| 2.3 | A password with leading, trailing and inner spaces | Backend: covered | `SignUpSignInIT.a2_3_and_2_8_nothingIsTrimmedOrCut` `PasswordPolicyTest.acceptance_2_3_spacesAreKeptExactly` |
| 2.4 | The same password in composed and decomposed Unicode form | Backend: covered | `SignUpSignInIT.a2_4_composedAndDecomposedPasswordsAreTheSame` `PasswordPolicyTest.acceptance_2_4_composedAndDecomposedFormsBecomeTheSamePassword` |
| 2.5 | Two passwords that differ only by a compatibility character, for example the "ﬁ" ligature against "fi" | Backend: covered | `PasswordPolicyTest.acceptance_2_5_compatibilityCharactersStayDistinct` |
| 2.6 | 16 code points as sent that become 14 after NFC | Backend: covered | `PasswordPolicyTest.acceptance_2_6_minimumIsMeasuredAfterNormalization` |
| 2.7 | 129 code points as sent that would become 128 after NFC | Backend: covered | `PasswordPolicyTest.acceptance_2_7_maximumAsSentIsCheckedFirst` |
| 2.8 | A 128-character password differing from another only in the last character | Backend: covered | `SignUpSignInIT.a2_3_and_2_8_nothingIsTrimmedOrCut` |
| 2.9 | A password on the breached list | Backend: covered | `ProductionProfileIT.a2_9_productionDoesNotStartWithoutASuitableBreachedPasswordList` `ProductionProfileIT.a2_9_withASuitableListProductionStartsAndRefusesAListedPassword` `OperatorModeIT.a2_9_theOperatorCommandRefusesToRunWithoutASuitableBreachedPasswordList` `BreachedPasswordsProductionSizeIT.a2_9_aSuitableListIsAcceptedInProductionAndSearchedLocally` `BreachedPasswordsTest.a2_9_productionRefusesToStartWithoutAList` `BreachedPasswordsTest.a2_9_productionRefusesAMissingOrUnreadableList` `BreachedPasswordsTest.a2_9_productionRefusesAnUnsuitableList` `BreachedPasswordsTest.a2_9_aListOfTheRightSizeIsStillRefusedWhenItsContentIsWrong` `BreachedPasswordsTest.a2_9_developmentAndTestsMayRunOnTheStarterListAlone` `BreachedPasswordsTest.a2_9_aListedPasswordIsRefusedAndAnUnlistedOneIsNot` `BreachedPasswordsTest.a2_9_theBuildToolWritesAListThatTheVerifierThenJudges` `BreachedPasswordsTest.a2_9_aRecordedDigestCatchesAListReplacedTogetherWithItsManifest` `PasswordPolicyTest.acceptance_2_9_blockedPasswords` Extended by the owner decision of 2026-11-25. Refusal of a listed password: unit tests, and over HTTP in production in `RealDatabaseIT`. Production start-up refused for a missing, unreadable or unsuitable list, for the server and for the operator command alike: `BreachedPasswordsTest`, `ProductionProfileIT`, `OperatorModeIT`. A suitable list accepted and searched locally: `BreachedPasswordsProductionSizeIT` and every test that starts the application as production does, using a synthetic list that meets the real requirements (100,000,005 entries). Not shown by any test: the real corpus itself, which is not in the repository; building and verifying it is a deployment step (`docs/backend-password-blocklist.md`). `a2_9_aRecordedDigestCatches...` covers the optional `APV_PASSWORD_BLOCKLIST_SHA256`: a list replaced together with its manifest is refused by the server and the operator command. |
| 2.10 | `apv-gakasinay-2026`; the email's first part plus `123`; the display name twice | Backend: covered | `PasswordPolicyTest.acceptance_2_10_passwordsBuiltFromTheContext` |
| 2.11 | A long passphrase that happens to contain the site name with 8 or more other characters | Backend: covered | `PasswordPolicyTest.acceptance_2_11_contextWordWithEnoughElseIsAccepted` |
| 2.12 | All lower-case letters, no digit, no symbol, 15 long, not on the list | Backend: covered | `PasswordPolicyTest.acceptance_2_12_noCompositionRules` |
| 2.13 | Stored hash | Backend: covered | `SignUpSignInIT.a2_13_storedHashesAreArgon2idWithUniqueSalts` `IdentitySecurityUnitTest.a2_13_and_2_14_hashesAreArgon2idAndWeakerOnesAreUpgraded` |
| 2.14 | Raise the work factor in configuration, sign in | Backend: covered | `ConcurrencyCorrectionsIT.a2_14_aSignInThatVerifiedAnOldPasswordCannotWriteItBackOrSignIn` `IdentitySecurityUnitTest.a2_14_argon2ParametersCanBeRaisedNeverLowered` `IdentitySecurityUnitTest.a2_13_and_2_14_hashesAreArgon2idAndWeakerOnesAreUpgraded` |
| 2.15 | Change password with a wrong current password | Backend: covered | `SignUpSignInIT.a2_15_and_2_16_changingThePassword` |
| 2.16 | Change password to the current one | Backend: covered | `SignUpSignInIT.a2_15_and_2_16_changingThePassword` |
| 2.17 | No outgoing network connection during any password check | Backend: covered | `IdentitySecurityUnitTest.a2_17_passwordChecksNeedNoNetwork` The same test runs a second time in CI inside a network namespace with no interface (`unshare --net`), where it first proves that a connection fails, then runs the policy, the blocklist, hashing and verification (evidence no-network). It covers the password code, not a whole sign-in over HTTP, which needs the database connection. |

## 3. Sessions

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 3.1 | Obtain an anonymous session with `GET /auth/csrf`, sign in, reuse the earlier identifier | Backend: covered | `SessionLifecycleIT.a3_1_anIdentifierKnownBeforeSignInIsWorthlessAfterwards` |
| 3.2 | Identifier before and after second-factor verification, enrollment confirmation and a password change | Backend: covered | `ConcurrencyCorrectionsIT.a3_2_aReplacedSessionIdentifierCannotBeWrittenBack` `SessionLifecycleIT.a3_2_theIdentifierChangesAtEveryChangeOfPrivilege` |
| 3.3 | Idle for longer than the idle timeout (clock moved), then `GET /me`; then a `POST` with the old token | Backend: covered | `SessionLifecycleIT.a3_3_idleTimeoutByRoleWhileTheRowIsStillThere` |
| 3.4 | Keep a session active past its absolute lifetime, then `GET /me`; then a `POST` with the old token | Backend: covered | `SessionLifecycleIT.a3_4_activityNeverMovesTheAbsoluteDeadline` |
| 3.5 | Deadlines in `Account.session` for donor and staff; `pendingExpiresAt`; anonymous session | Backend: covered | `SessionLifecycleIT.a3_5_deadlinesByKindOfSession` |
| 3.6 | Sign out, then reuse the cookie on `GET /me` and on a `POST` with the old token | Backend: covered. Browser repeat: joint integration | `SessionLifecycleIT.a3_6_and_3_7_signOut` |
| 3.7 | Sign out with no session, and twice | Backend: covered | `SessionLifecycleIT.a3_6_and_3_7_signOut` |
| 3.8 | Two browsers signed in; change the password in one | Backend: covered. Browser repeat: joint integration | `SessionLifecycleIT.a3_8_aPasswordChangeEndsEveryOtherSession` |
| 3.9 | Production profile: cookie attributes | Backend: covered | `RealDatabaseIT.a1_10_a3_9_productionHasClosedSignUpAndTheSecureHostCookie` `SessionLifecycleIT.a3_9_oneCookieWithTheRightAttributes` |
| 3.10 | Start outside `dev` and `test` with a non-`Secure` session cookie configured | Backend: covered | `IdentitySecurityUnitTest.a3_10_productionDefaultsAreTheSecureHostCookieAndClosedSignUp` `IdentitySecurityUnitTest.a3_10_and_1_11_productionRefusesToStartWithDevelopmentConveniences` |
| 3.11 | Scripts in the page try to read the session cookie | Browser only: joint integration | Server part covered by 3.9 (`HttpOnly`). Reading the cookie from page scripts needs a browser. |
| 3.12 | A session identifier that was never issued | Backend: covered | `SessionLifecycleIT.a3_12_and_3_13_sessionsThatGrantNothing` |
| 3.13 | An anonymous session calls `GET /me` and a protected route | Backend: covered | `SessionLifecycleIT.a3_12_and_3_13_sessionsThatGrantNothing` |
| 3.14 | Fully signed in, call `/auth/login`, `/auth/register`, `/auth/set-password` | Backend: covered. Browser repeat: joint integration | `SessionLifecycleIT.a3_14_aSignedInSessionIsNeverSignedInOver` |
| 3.15 | With a pending second factor, sign in again as another account | Backend: covered | `ConcurrencyCorrectionsIT.a3_15_aFailedSignInEndsThePendingSignInAndARefusedRequestDoesNot` `SessionLifecycleIT.a3_15_aPendingSignInIsReplacedByANewSignIn` |
| 3.16 | Wait past `pendingExpiresAt`, then call `/auth/mfa/verify`, `/enroll`, `/confirm` with the token the page still holds | Backend: covered | `SessionLifecycleIT.a3_16_aPendingSignInExpiresAfterTenMinutes` |
| 3.16a | Same, but each call made with a fresh token of a new anonymous session | Backend: covered | `SessionLifecycleIT.a3_16_aPendingSignInExpiresAfterTenMinutes` |
| 3.17 | Drop the answer of each state-changing call on the way back, then follow the table in `identity-and-security.md` 4.6 | Browser only: joint integration | Needs the frontend. The server side of each row (what a repeated or follow-up call answers) is covered by 3.14, 3.18 and group 14. |
| 3.18 | Every identity operation called in every session state, each with a valid token for that session | Backend: covered | `SessionLifecycleIT.a3_18_everyOperationInEveryWrongStateHasOneAnswer` |

## 4. CSRF

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 4.1 | `GET /auth/csrf` with no cookie | Backend: covered | `CsrfAndOriginIT.a4_1_theTokenEndpointBootstrapsAnAnonymousSession` `JointAcceptanceFindingsIT.a4_1_simultaneousFirstTokenReadsAfterASessionChangeGiveOneTokenAndNoError` `JointAcceptanceFindingsIT.a4_1_...` (first real-browser run, finding 3): twelve simultaneous first reads of the token right after sign-in, after a password change, on a pending sign-in and after the second factor, 23 rounds: every read is 200, all hold the same token, exactly one is stored, and it is stored before anyone asks. |
| 4.2 | Each `POST` endpoint with no CSRF header | Backend: covered | `CsrfAndOriginIT.a4_2_a4_3_a4_7_everyPostNeedsTheTokenIncludingThePublicOnes` `CsrfTokenHandlerTest.a4_2_theGenuineMaskedTokenResolvesToTheStoredOne` `CsrfTokenHandlerTest.a4_2_otherShapesAreNotErrorsEither` |
| 4.3 | With a made-up token | Backend: covered | `CsrfAndOriginIT.a4_2_a4_3_a4_7_everyPostNeedsTheTokenIncludingThePublicOnes` `JointAcceptanceFindingsIT.a4_3_anyTokenOfValidShapeIsRefused403OnEveryPost` `CsrfTokenHandlerTest.a4_3_noValueOfValidShapeIsAnErrorAndNoneResolvesToTheStoredToken` `JointAcceptanceFindingsIT.a4_3_...` (first real-browser run, finding 2): 300 tokens of valid length and alphabet, random or the genuine one with one to four characters changed, on each of the nine POSTs, on an anonymous and on a signed-in session: every answer is 403 `csrf_invalid` with the Error body and nothing changes. `CsrfTokenHandlerTest` covers the handler with 5,000 such values and other shapes. |
| 4.4 | Token fetched before sign-in, used after sign-in | Backend: covered. Browser repeat: joint integration | `CsrfAndOriginIT.a4_4_a4_6_aTokenDiesWithItsSession` |
| 4.5 | Token from browser A used with browser B's session cookie | Backend: covered | `CsrfAndOriginIT.a4_5_aTokenBelongsToOneBrowser` |
| 4.6 | A `POST` with the token of a session that was signed out, rotated by a password change, or has expired; with the old cookie, with a new cookie, and with no cookie | Backend: covered | `CsrfAndOriginIT.a4_4_a4_6_aTokenDiesWithItsSession` `CsrfAndOriginIT.a4_6_aTokenOfAnExpiredSessionIsRefused` |
| 4.7 | Sign-up, sign-in and sign-out with no token | Backend: covered | `CsrfAndOriginIT.a4_2_a4_3_a4_7_everyPostNeedsTheTokenIncludingThePublicOnes` |
| 4.8 | Two tokens fetched in a row for one session | Backend: covered | `CsrfAndOriginIT.a4_8_twoTokensOfOneSessionDifferAndBothWork` |
| 4.9 | A wrong password sent with a bad token, six times | Backend: covered | `CsrfAndOriginIT.a4_9_aRefusedTokenCountsAgainstNothing` |
| 4.10 | A cross-site form post (`application/x-www-form-urlencoded`) to sign-in, as a browser sends it: `Origin` of the other site | Backend: covered | `CsrfAndOriginIT.a4_10_and_4_11_requestsFromAnotherSiteAreRefusedBeforeAnythingElse` |
| 4.10a | The same body and content type sent with no `Origin` and no `Sec-Fetch-Site` | Backend: covered | `CsrfAndOriginIT.a4_10a_and_4_11a_requestsFromThisSitePassTheOriginCheck` |
| 4.11 | A `POST` with: `Origin` of another site; `Origin: null`; no `Origin` and `Sec-Fetch-Site` of `cross-site`, `same-site` or `none`; the site's own `Origin` with `Sec-Fetch-Site: cross-site` | Backend: covered | `CsrfAndOriginIT.a4_10_and_4_11_requestsFromAnotherSiteAreRefusedBeforeAnythingElse` |
| 4.11a | A `POST` with the site's own `Origin` and `Sec-Fetch-Site: same-origin`; with the site's own `Origin` only; with neither header | Backend: covered | `CsrfAndOriginIT.a4_10a_and_4_11a_requestsFromThisSitePassTheOriginCheck` |
| 4.11b | A `GET` to a public endpoint and to `/me` carrying another site's `Origin` or `Sec-Fetch-Site: cross-site` | Backend: covered | `CsrfAndOriginIT.a4_11b_and_4_12_getRequestsNeedNoTokenAndAreNotSubjectToTheOriginCheck` |
| 4.12 | `GET` endpoints with no token | Backend: covered | `CsrfAndOriginIT.a4_11b_and_4_12_getRequestsNeedNoTokenAndAreNotSubjectToTheOriginCheck` |
| 4.13 | Tab A shows account X. Tab B signs out and signs in as account Y. Tab A submits a password change. | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 4.14 | Stale token on **every** `POST` in turn: sign-in, sign-up, sign-out, password change, set password, and each second-factor operation, with the identity unchanged | Browser only: joint integration | API part: `CsrfAndOriginIT.a4_2_a4_3_a4_7_...` and `a4_4_a4_6_...` send a stale or missing token to every POST and get 403 `csrf_invalid` with nothing changed. That the frontend does not resend is a browser test. |
| 4.15 | After 4.14, the person submits again | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 4.16a | Tab A is on the second-factor challenge of account X. Tab B starts a sign-in as account Y and is also pending. Tab A submits its code. | Browser only: joint integration | API part: `SessionLifecycleIT.a3_15_...`. |
| 4.16b | A `GET` that fails while the token is stale | Backend: covered | `CsrfAndOriginIT.a4_11b_and_4_12_getRequestsNeedNoTokenAndAreNotSubjectToTheOriginCheck` |
| 4.17 | Where the token lives in the browser | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |

## 5. Authorization

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 5.1 | Every route under `/api` that is not public, with no session | Backend: covered | `AuthorizationAndHeadersIT.a5_1_protectedRoutesAnswer401AsJsonNeverARedirect` |
| 5.2 | A route registered in the application with no access rule | Backend: covered | `AuthorizationAndHeadersIT.a5_2_noRouteExistsWithoutADeclaredAccessRule` |
| 5.3 | `GET /me` as donor, admin, admin with `can_disburse`, super admin | Backend: covered | `AuthorizationAndHeadersIT.a5_3_everyRoleGetsExactlyTheOperationalPermissions` |
| 5.4 | The permission function behind the planned matrix, for each role and `can_disburse` | Backend: covered | `IdentitySecurityUnitTest.a5_4_thePermissionFunctionEqualsTheMatrix` |
| 5.5 | Sign-up body with `role`, `permissions`, `canDisburse`, `emailVerified` or `id` | Backend: covered | `SignUpSignInIT.a5_5_and_5_6_signUpCannotChooseARoleAndUnknownPropertiesAreRefused` |
| 5.6 | Any identity request with an extra property | Backend: covered | `SignUpSignInIT.a5_5_and_5_6_signUpCannotChooseARoleAndUnknownPropertiesAreRefused` |
| 5.7 | A donor calls a staff-only test route; an admin calls a super-admin-only test route | Partly covered | No staff-only or super-admin-only business route exists in this milestone. Covered as far as routes exist: a donor calling the staff-only `recovery-codes` gets 403 `forbidden` (`SecondFactorIT.a6_16_...`, `SessionLifecycleIT.a3_18_...`). The admin against super-admin-only case has nothing to call yet; no test-only route was added to production code. |
| 5.8 | Account A tries to act on account B by any identifier in path, query, header or body | Backend: covered | `AuthorizationAndHeadersIT.a5_8_noOperationAcceptsAnAccountIdentifier` |
| 5.9 | Demote an admin, or remove `can_disburse`, while signed in | Backend: covered | `AuthorizationAndHeadersIT.a5_9_removingAPermissionEndsTheSessionFirst` |
| 5.10 | Disable an account while signed in | Backend: covered | `AuthorizationAndHeadersIT.a5_10_aDisabledAccountLosesItsSessionsAndCannotSignIn` |
| 5.11 | A session whose row survived deletion but whose security version is old: `GET /me`; a password change with its still-valid token and a **wrong** current password; the same with the right one | Backend: covered | `AuthorizationAndHeadersIT.a5_11_aSessionWhoseRowSurvivedIsRefusedByItsSecurityVersion` |
| 5.12 | A privileged write and a demotion of its account, started together, many times | Backend: covered | `AuthorizationAndHeadersIT.a5_12_and_5_13_aWriteRacingARevocationNeverLeavesALiveSession` `ConcurrencyCorrectionsIT.a5_12_aPasswordChangeThatVerifiedAnOldPasswordChangesNothing` `ConcurrencyCorrectionsIT.a5_12_verificationThatRacesARevocationNeitherSignsInNorSpendsTheCode` `ConcurrencyCorrectionsIT.a5_12_regenerationThatRacesAChangeTouchesNeitherTheNewCodesNorTheFactor` `ConcurrencyCorrectionsIT.a5_12_enrollmentStepsThatRaceAChangeDoNothing` `ConcurrencyCorrectionsIT.a5_12_verificationAndStaffRecoveryTogetherNeverLeaveASession` |
| 5.13 | A read in flight when the demotion commits; a request starting just after | Backend: covered | `AuthorizationAndHeadersIT.a5_12_and_5_13_aWriteRacingARevocationNeverLeavesALiveSession` `AuthorizationAndHeadersIT.a5_9_removingAPermissionEndsTheSessionFirst` |

## 6. Second factor

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 6.1 | Activated staff inside the enrollment window signs in | Backend: covered. Browser repeat: joint integration | `SecondFactorIT.a6_1_staffWithoutASecondFactorCanOnlyEnroll` `SecondFactorIT.a6_1_theEnrollmentWindowIsOneHourFromTheCredential` |
| 6.2 | Enroll, confirm with a correct code | Backend: covered. Browser repeat: joint integration | `SecondFactorIT.a6_2_enrollmentMakesTheSessionFullySignedIn` |
| 6.3 | Confirm with a wrong code; after the pending secret expired; with a code from a secret that was replaced by a second `enroll` | Backend: covered | `SecondFactorIT.a6_3_and_6_5b_wrongStaleAndReplacedCodesKeepThePendingSession` |
| 6.4 | Two tabs start enrollment; the first tab confirms with its own code | Backend: covered | `SecondFactorIT.a6_4_onlyTheLatestPendingSecretCounts` |
| 6.5 | Simultaneous confirmations with a correct code, repeated many times, in each arrangement of `identity-and-security.md` 5.3 | Backend: covered | `SecondFactorIT.a6_5_simultaneousConfirmationsHaveExactlyOneWinner` |
| 6.5a | Start enrollment inside the window, confirm with a correct code after the window closed (clock moved) | Backend: covered | `SecondFactorIT.a6_5a_theEnrollmentWindowIsCheckedAgainAtConfirmation` |
| 6.5b | Wrong code at confirmation, then a correct one for the same pending secret | Backend: covered | `SecondFactorIT.a6_3_and_6_5b_wrongStaleAndReplacedCodesKeepThePendingSession` |
| 6.6 | Staff with a second factor signs in | Backend: covered. Browser repeat: joint integration | `SecondFactorIT.a6_6_verificationCompletesAPendingSignIn` |
| 6.7 | The same code in two simultaneous verification requests, many times | Backend: covered | `SecondFactorIT.a6_7_and_6_8_aCodeWorksOnceAlsoAtTheSameInstant` |
| 6.8 | The same code again within its 30 seconds, in a new sign-in | Backend: covered | `SecondFactorIT.a6_7_and_6_8_aCodeWorksOnceAlsoAtTheSameInstant` |
| 6.9 | Codes for the previous and next step; a code two steps away | Backend: covered | `SecondFactorIT.a6_9_oneStepEitherSideIsAcceptedAndNoFurther` `IdentitySecurityUnitTest.a6_9_authenticatorCodesMatchTheRfcTestVectors` `IdentitySecurityUnitTest.a6_9_aCodeMatchesOnlyWithinOneStepEitherSide` |
| 6.10 | Four wrong codes, then a fifth, then a correct one | Backend: covered | `ConcurrencyCorrectionsIT.a6_10_wrongCodesSentTogetherAreCountedExactly` `ConcurrencyCorrectionsIT.a6_10_aCorrectCodeAmongFourWrongOnesSignsInAndStaysSignedIn` `ConcurrencyCorrectionsIT.a6_10_theFifthWrongCodeAndACorrectCodeTogetherHaveOneConsistentOutcome` `ConcurrencyCorrectionsIT.a6_10_aPendingSignInThatIsOverDoesNotExistEvenIfItsSessionRowDoes` `SecondFactorIT.a6_10_fourWrongCodesKeepThePendingSignInTheFifthEndsIt` |
| 6.10a | Every way a session can be lost (expiry, sign-out elsewhere, disablement, security-version change, rotation), then every protected operation that checks a password or code (excluding operations accepting anonymous), assuming earlier checks other than the specified CSRF condition pass | Backend: covered | `SessionLifecycleIT.a3_3_idleTimeoutByRoleWhileTheRowIsStillThere` `SessionLifecycleIT.a3_4_activityNeverMovesTheAbsoluteDeadline` `SessionLifecycleIT.a3_6_and_3_7_signOut` `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` `PrecedenceIT.a14_12_aDisabledAccountWhoseSessionRowsWereDeleted` `PrecedenceIT.a14_13_and_14_14_aRevokedIdentityWhoseRowSurvivedNeverReachesThePasswordCheck` `PrecedenceIT.a14_15_theSameForRecoveryCodes` `PrecedenceIT.a14_16_aPendingSessionOfADisabledAccountDoesNotConsumeTheCode` |
| 6.10b | Every wrong password or code on a live session, except the fifth code | Backend: covered | `SignUpSignInIT.a2_15_and_2_16_changingThePassword` `SecondFactorIT.a6_10_fourWrongCodesKeepThePendingSignInTheFifthEndsIt` `SecondFactorIT.a6_14_and_6_15_replacingRecoveryCodes` `SecondFactorIT.a6_3_and_6_5b_wrongStaleAndReplacedCodesKeepThePendingSession` `PrecedenceIT.a14_28_and_14_29_validationBeforeThePasswordAndThePasswordCountedOnce` |
| 6.11 | Sign in with a recovery code; reuse it | Backend: covered | `SecondFactorIT.a6_11_and_6_12_recoveryCodesWorkOnce` |
| 6.12 | One recovery code in two simultaneous requests, many times | Backend: covered | `SecondFactorIT.a6_11_and_6_12_recoveryCodesWorkOnce` |
| 6.13 | Authenticator method with a recovery-shaped code, and the reverse | Backend: covered | `SecondFactorIT.a6_13_aCodeMustHaveTheShapeOfItsMethod` |
| 6.14 | Regenerate recovery codes with password and code | Backend: covered | `SecondFactorIT.a6_14_and_6_15_replacingRecoveryCodes` |
| 6.15 | Regenerate with a recovery code in place of the authenticator code, or with a wrong password | Backend: covered | `SecondFactorIT.a6_14_and_6_15_replacingRecoveryCodes` |
| 6.16 | A donor calls any second-factor endpoint | Backend: covered | `SecondFactorIT.a6_16_aDonorHasNoSecondFactorSteps` |
| 6.17 | The verifier against RFC 6238 test vectors | Backend: covered | `IdentitySecurityUnitTest.a6_9_authenticatorCodesMatchTheRfcTestVectors` |
| 6.18 | Database content after enrollment | Backend: covered | `SecondFactorIT.a6_18_noSecretAndNoRecoveryCodeIsStoredInTheClear` `IdentitySecurityUnitTest.a6_18_encryptionIsAuthenticatedAndNeverRepeats` |
| 6.19 | Password reset for staff, then sign-in | Backend: covered | `SecondFactorIT.a6_19_aPasswordResetDoesNotRemoveTheSecondFactor` |
| 6.20 | `recover-staff-access`, then sign in with the **old** password | Backend: covered | `SecondFactorIT.a6_20_and_6_21_staffRecoveryMakesTheOldPasswordUseless` |
| 6.21 | After 6.20: redeem the recovery credential, choose a new password, sign in | Backend: covered | `SecondFactorIT.a6_20_and_6_21_staffRecoveryMakesTheOldPasswordUseless` |
| 6.22 | A staff account with a password and no second factor, after its enrollment window closed | Backend: covered | `ConcurrencyCorrectionsIT.a6_22_anExpiredEnrollmentWindowDoesNotRemoveACredentialIssuedMeanwhile` `SecondFactorIT.a6_22_aPasswordAloneNeverOpensEnrollmentOnceTheWindowHasClosed` |
| 6.23 | The QR code on the enrollment screen | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |

## 7. One-time credentials, operator command and the super administrator invariant

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 7.1 | The application starts on an empty database | Backend: covered | `StaffAdministrationIT.a7_1_anEmptyDatabaseHasNoAccountAndNothingCanSignIn` |
| 7.2 | `create-super-admin` on an empty database | Backend: covered | `OperatorModeIT.a7_2_theOperatorCommandRunsWithoutAWebServerAndMigratesAnEmptyDatabase` `RealDatabaseIT.a7_2_a7_3_a7_19_theOperatorCreatesTheFirstSuperAdministratorWhoThenSignsIn` `StaffAdministrationIT.a7_2_and_7_12_aNewStaffAccountHasNoPasswordAndItsCredentialIsStoredHashed` |
| 7.3 | Activate and enroll that account | Backend: covered | `RealDatabaseIT.a7_2_a7_3_a7_19_theOperatorCreatesTheFirstSuperAdministratorWhoThenSignsIn` `StaffAdministrationIT.a7_3_theFirstUsableSuperAdministratorBootstrapsTheSiteOnce` |
| 7.4 | Two `create-super-admin` runs at the same time on an empty database, then both activated and enrolled at the same time | Backend: covered | `StaffAdministrationIT.a7_4_twoSuperAdministratorsCreatedAndEnrolledAtTheSameTime` |
| 7.5 | Set a password with the credential; use the credential again | Backend: covered | `StaffAdministrationIT.a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame` `StaffAdministrationIT.a7_5_aWeakPasswordIsRefusedWithoutSpendingTheCredential` |
| 7.6 | Expired, malformed (any text), unknown and already used credentials | Backend: covered | `StaffAdministrationIT.a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame` |
| 7.7 | A credential that is a number, or missing | Backend: covered | `StaffAdministrationIT.a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame` |
| 7.8 | Issue a second credential | Backend: covered | `StaffAdministrationIT.a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame` |
| 7.9 | Two simultaneous redemptions of one credential | Backend: covered | `StaffAdministrationIT.a7_9_twoSimultaneousRedemptionsOfOneCredential` |
| 7.10 | Setting a password signs nobody in | Backend: covered | `StaffAdministrationIT.a7_5_to_7_8_aCredentialWorksOnceAndEveryRefusalLooksTheSame` |
| 7.11 | Reset for an account with sessions | Backend: covered | `StaffAdministrationIT.a7_11_aResetEndsEverySessionOfTheAccount` |
| 7.12 | Database content | Backend: covered | `StaffAdministrationIT.a7_2_and_7_12_aNewStaffAccountHasNoPasswordAndItsCredentialIsStoredHashed` |
| 7.13 | One usable super administrator: disable it, demote it, delete it, run `recover-staff-access` on it | Backend: covered | `StaffAdministrationIT.a7_13_theOnlyUsableSuperAdministratorCannotBeRemoved` Disable and staff recovery through the administration code; demote and delete through plain SQL (`a7_17`), because no command demotes or deletes an account. |
| 7.14 | Two usable super administrators: remove one, then the other | Backend: covered | `StaffAdministrationIT.a7_14_and_7_16_onlyUsableSuperAdministratorsCount` |
| 7.15 | **Two usable super administrators, each disabled by a different transaction at the same time, repeated many times**; also one disabled while the other is demoted, and one disabled while `recover-staff-access` runs on the other | Backend: covered | `StaffAdministrationIT.a7_15_twoRemovalsAtTheSameTimeNeverLeaveNone` |
| 7.16 | A second super administrator that is enabled and activated but has no second factor; remove the first | Backend: covered | `StaffAdministrationIT.a7_14_and_7_16_onlyUsableSuperAdministratorsCount` |
| 7.17 | A statement that changes a super administrator without taking the guard | Backend: covered | `StaffAdministrationIT.a7_17_aChangeThatSkipsTheGuardIsRefusedAtCommit` |
| 7.18 | The recovery path: one super administrator who lost everything | Backend: covered | `StaffAdministrationIT.a7_18_recoveryWhenTheOnlySuperAdministratorLostEverything` |
| 7.19 | Process list and shell history after operator commands | Partly covered | `RealDatabaseIT.a7_2_a7_3_a7_19_theOperatorCreatesTheFirstSuperAdministratorWhoThenSignsIn` Structural only: the command takes its name as the single argument and asks for everything else on the terminal (`RealDatabaseIT`, `OperatorModeIT`); the credential is printed, never passed. The process list and shell history were not inspected. |
| 7.20 | A donor recovery request supported only by facts the requester supplies | Partly covered | The procedure is documented in `backend/README.md`; the command states the rule, refuses when no identity check is entered and records the answer in the audit log (`RealDatabaseIT.a7_2_a7_3_a7_19_...`). Whether an operator follows it is not something a test can show. |

## 8. Rate limiting

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 8.1 | Six failed sign-ins for one address from one client address | Backend: covered | `RateLimitIT.a8_1_a8_2_a8_4_theSixthFailureFromOneAddressIsRefusedAndOnlyThere` |
| 8.2 | Same, then from another client address | Backend: covered | `RateLimitIT.a8_1_a8_2_a8_4_theSixthFailureFromOneAddressIsRefusedAndOnlyThere` |
| 8.3 | 21 failures for one address across many client addresses | Backend: covered | `RateLimitCorrectionsIT.a8_3_theCooldownStartsOnceWhenManyRequestsArriveAtTheBoundary` `RateLimitIT.a8_3_manyAddressesAgainstOneAccountGetABoundedPause` |
| 8.4 | Throttled attempts for an existing and an unknown address | Backend: covered | `RateLimitIT.a8_1_a8_2_a8_4_theSixthFailureFromOneAddressIsRefusedAndOnlyThere` |
| 8.5 | Fifty wrong passwords for one address and client address sent at the same instant | Backend: covered | `RateLimitCorrectionsIT.a8_5_aWindowLimitAdmitsExactlyItsNumberOfSimultaneousAttempts` `RateLimitIT.a8_5_and_8_12_fiftySimultaneousGuessesVerifyAtMostFivePasswords` |
| 8.6 | `Retry-After` on every 429 the suite produces | Backend: covered | `RateLimitIT.everyRefusalSaysWhenToComeBack (after every rate-limit test)` |
| 8.7 | Sign-up, one-time credential, CSRF and options limits | Backend: covered | `RateLimitCorrectionsIT.a8_7_passwordChangeAndRecoveryCodesShareOneClientAddressBudgetSeparateFromSignIn` `RateLimitIT.a8_7_signUpCredentialAndTokenLimits` The shared budget for password change and recovery-code regeneration (owner decision of 2026-11-25): fifteen requests to each reach the limit of thirty on one counter, request 31 on either is 429 with `Retry-After` exactly to the end of the window, before state, body and secrets; CSRF, type, method and origin refusals come first and count toward nothing; sign-in is a separate budget in both directions. |
| 8.8 | A forged forwarded-for header from an untrusted connection | Backend: covered | `ClientAddressTest.a8_8_aForgedHeaderFromAnUntrustedConnectionIsIgnored` `ClientAddressTest.a8_8_onlyTheEntryTheTrustedProxyAddedCounts` |
| 8.9 | Wrong current password on `change-password` and on `recovery-codes`; wrong code on `recovery-codes` and `confirm` | Backend: covered | `RateLimitCorrectionsIT.a8_9_onePasswordCounterPerAccountAcrossSignInPasswordChangeAndRecoveryCodes` `RateLimitCorrectionsIT.a8_9_protectedPasswordOperationsHaveAClientAddressBudgetCountedBeforeTheBodyIsRead` `RateLimitIT.a8_9_secretsOfSignedInOperationsDrawOnTheAccountCounters` `RateLimitIT.a8_9_wrongCodesAtEnrollmentAndWrongPasswordsOnRecoveryCodesAreCountedToo` |
| 8.10 | Counters in the database | Backend: covered | `RateLimitIT.a8_10_countersHoldHashesNotAddresses` |
| 8.11 | More simultaneous sign-ins than hash slots, from many client addresses | Backend: covered | `HashSaturationIT.a8_11_moreSignInsThanHashSlotsNeverRunMoreThanFourHashesAndTheRestAreToldToComeBack` `IdentitySecurityUnitTest.a8_11_whenEveryHashSlotIsBusyARequestIsToldToComeBack` `IdentitySecurityUnitTest.a8_11_hashingLimitsCanBeMadeStricterNeverLooser` `HashSaturationIT` sends forty simultaneous sign-ins over HTTP with the accepted limits (4 at once, 2 s): every answer is 401 or 503 with `Retry-After: 5`, the peak number of simultaneous hashes observed in the process is at most 4, and a password that was turned away leaves no failure record. Heap use is recorded as evidence, not asserted. The unit test covers the bounds on the settings. |
| 8.12 | One client address trying to occupy all hash slots | Backend: covered | `RateLimitIT.a8_5_and_8_12_fiftySimultaneousGuessesVerifyAtMostFivePasswords` |
| 8.13 | A successful sign-in after four failures | Backend: covered | `RateLimitIT.a8_13_aSuccessClearsThePairCounter` |

## 9. Caching, headers and leaks

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 9.1 | Every answer of every identity operation the suite produces, at every status: 2xx, 400, 401, 403, 405, 409, 413, 415, 429, 500, 503 | Backend: covered | `AuthorizationAndHeadersIT.a9_1_a9_3_a9_4_a9_5_headersOnEveryIdentityAnswer` Also applied after every identity test to every answer it received (`IdentityIntegration.everyIdentityAnswerMatchesTheContract`). Statuses produced by the suite: 200, 202, 204, 400, 401, 403, 404, 405, 409, 413, 415, 429, 500. A 503 over HTTP is not produced (see 8.11). |
| 9.2 | The seven public endpoints, with and without a session cookie | Backend: covered | `AuthorizationAndHeadersIT.a9_2_and_13_2_publicEndpointsDoNotDependOnTheSessionAndSetNoCookie` |
| 9.3 | Every 405; every 429; every identity 503 | Backend: covered | `AuthorizationAndHeadersIT.a9_1_a9_3_a9_4_a9_5_headersOnEveryIdentityAnswer` `RateLimitIT.everyRefusalSaysWhenToComeBack` `IdentitySecurityUnitTest.a8_11_whenEveryHashSlotIsBusyARequestIsToldToComeBack` |
| 9.4 | Security headers on API responses | Backend: covered | `AuthorizationAndHeadersIT.a9_1_a9_3_a9_4_a9_5_headersOnEveryIdentityAnswer` |
| 9.5 | No CORS headers on any response; a preflight from another origin | Backend: covered | `AuthorizationAndHeadersIT.a9_1_a9_3_a9_4_a9_5_headersOnEveryIdentityAnswer` |
| 9.6 | Body over 16 KiB; body that is not JSON; `Content-Type` other than JSON | Backend: covered | `AuthorizationAndHeadersIT.a9_6_and_9_7_bodies` |
| 9.7 | `POST /auth/logout` and `/auth/mfa/enroll` with no body and no `Content-Type`; with a non-empty body | Backend: covered | `AuthorizationAndHeadersIT.a9_6_and_9_7_bodies` |
| 9.8 | Application logs and audit rows after the whole suite, searched for every password, credential, secret, code, session identifier and CSRF token used | Backend: covered | `AuditIT.a12_1_and_9_8_everyEventIsRecordedOnceAndNoSecretIsWrittenAnywhere` `JointAcceptanceFindingsIT.a9_8_aRealDatabaseErrorThatNamesASessionIsLoggedWithoutItsIdentifier` `LogInspectionIT.a9_8_noSecretIsWrittenToTheApplicationLog` `SensitiveLogFilterTest.a9_8_identifierShapesAndHashesAreReplaced` `SensitiveLogFilterTest.a9_8_rawInputLoggersAreSilencedBelowInfo` `SensitiveLogFilterTest.a9_8_theInstalledConfigurationAppliesItAtEveryLevel` `SensitiveLogFilterTest.a9_8_exceptionMessagesAndTheirCausesAreRedacted` `SensitiveLogFilterTest.a9_8_noIdentifierInAPrintedStackTrace` `LogInspectionIT` captures the console of the whole journey with the application, Spring Security, Session, Web, JDBC and transactions, the embedded server, the connection pool and the database driver at TRACE, and searches it for every password, CSRF token, one-time credential, authenticator secret and recovery code, and for every session identifier the journey ever had (anonymous, pending, signed-in; live, replaced, ended) in stored and in cookie form. No exemption for retired identifiers. Authenticator codes are matched as standalone values, not as arbitrary substrings; occurrences inside longer numbers are counted separately in the evidence. `SensitiveLogFilterTest` covers the safeguard itself, including the installed configuration at TRACE. Limits: values that have no shape (passwords, codes, credentials) are kept out by never being logged, which the search confirms for this journey and cannot prove for every code path. Exceptions attached to log lines are redacted like messages (first real-browser run, finding 4): `SensitiveLogFilterTest` covers the message, causes and suppressed exceptions and the printed stack trace, and `JointAcceptanceFindingsIT` logs two real PostgreSQL errors that quote a session row key and a session identifier and finds neither, nor the cookie form, in the output. Audit rows and identity tables are searched by `AuditIT`. |
| 9.9 | Every response of the suite validated against `openapi.yaml` | Backend: covered | `IdentityIntegration.everyIdentityAnswerMatchesTheContract (after every test)` |
| 9.10 | Forced server error in an identity endpoint | Backend: covered | `AuthorizationAndHeadersIT.a9_10_aServerErrorSaysNothingAboutTheServer` |

## 10. Browser

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 10.1 | Address bar, history and every request URL during sign-up, sign-in, second factor, enrollment, set password and recovery codes | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.2 | A one-time credential arriving in a link fragment | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.3 | `localStorage`, `sessionStorage`, IndexedDB and script-written cookies after the whole journey | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.4 | Console output and any error or analytics reporting during the journey, including failures | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.5 | Sign out | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6 | The session is lost while a private screen is shown; then the page makes a `GET`, and separately a `POST` | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6a | Wrong current password on the password-change screen | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6b | Wrong authenticator code at sign-in, attempts one to four | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6c | The fifth wrong code, then the person types a correct one anyway | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6d | Wrong code on recovery-code regeneration | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6e | Load the application while the session is pending a second factor, and while pending enrollment | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.6f | A pending sign-in expires while its challenge is shown, then the person submits a code | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.7 | Another tab signs out, or signs in as another account | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.8 | Back button after signing out, including restoration from the back-forward cache | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.9 | Every identity screen loaded from the production build with the page content policy enforced | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.9a | The colour theme under that policy | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.10 | Real-API mode with the backend stopped, or answering 500 | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.11 | A production build | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.12 | Each identity screen in English and in French | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.13 | Each screen in the light and the dark theme, and at phone width | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.14 | Each screen with the keyboard only | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.15 | Errors with assistive technology | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 10.16 | Browser autofill and a password manager | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |

## 11. Methods and routes

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 11.1 | `POST /api/cases`, `DELETE /api/me`, `GET /api/auth/login` | Backend: covered | `CsrfAndOriginIT.a11_1_knownPathsWithAnUnsupportedMethodAnswer405WithAllow` |
| 11.2 | Any method on an unknown path | Backend: covered | `CsrfAndOriginIT.a11_2_and_11_3_unknownPathsAndNoCrossOriginGrant` |
| 11.3 | `OPTIONS` on any path | Backend: covered | `CsrfAndOriginIT.a11_2_and_11_3_unknownPathsAndNoCrossOriginGrant` |

## 12. Audit

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 12.1 | Each event in `identity-and-security.md` 10.1 | Backend: covered | `AuditIT.a12_1_and_9_8_everyEventIsRecordedOnceAndNoSecretIsWrittenAnywhere` |
| 12.2 | Update or delete an audit row | Backend: covered | `AuditIT.a12_2_theAuditLogIsAppendOnly` |
| 12.3 | A failed sign-in for an unknown address | Backend: covered | `AuditIT.a12_3_aFailedSignInForAnUnknownAddressIsRecordedWithoutTheAddress` |

## 13. Nothing public changed, and development data stays in development

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 13.1 | The whole milestone 0B suite: 114 backend tests, contract checks, frontend parity | Backend: covered | `CI: Backend checks, Contract checks, Frontend checks, Live public API integration` Evidence is the CI run: the 114 milestone 0B backend tests are part of the same `verify`, and the contract, frontend and live-integration workflows run on the same commit. Two 0B assertions were changed for 11.1 (405 instead of 404). |
| 13.2 | Full-dataset acceptance with a signed-in session cookie present | Backend: covered | `AuthorizationAndHeadersIT.a9_2_and_13_2_publicEndpointsDoNotDependOnTheSessionAndSetNoCookie` `PublicApiWithSessionIT.a13_2_theWholePublicApiIsTheSameWithAndWithoutASession` `PublicApiWithSessionIT` loads the full dataset and compares every public path of every published case for a donor, a super administrator and a pending sign-in with the same path without a cookie: bodies and ETags identical, no `Set-Cookie`. |
| 13.3 | The fixture loader and the development seed on a database with no disposable marker, empty or not | Backend: covered | `RealDatabaseIT.a13_3_and_13_7_anUnmarkedDatabaseTakesNoFictionalDataAndNoDevelopmentAccount` `StaffAdministrationIT.a13_3_and_13_6_aRealDatabaseNeverAcceptsFictionalDataAndCannotBeMadeDisposableAgain` `DevelopmentDataIsolationIT.a13_3_theFixtureLoaderRefusesAnEmptyDatabaseThatWasNotMarkedDisposable` |
| 13.4 | The same on a database explicitly marked disposable | Backend: covered | `StaffAdministrationIT.a13_4_theFixtureLoaderLeavesIdentityDataAlone` |
| 13.5 | The operator command on a disposable database | Backend: covered | `StaffAdministrationIT.a13_5_theOperatorCommandRefusesADisposableDatabase` |
| 13.6 | After the operator command has run once on a database: mark it disposable | Backend: covered | `RealDatabaseIT.a13_6_aRealDatabaseCanNeverBecomeDisposable` `StaffAdministrationIT.a13_3_and_13_6_aRealDatabaseNeverAcceptsFictionalDataAndCannotBeMadeDisposableAgain` |
| 13.7 | The development seed | Backend: covered | `DevProfileHttpIT.a13_7_theDevelopmentSeedCreatesThreeFictionalAccountsWithGeneratedPasswords` `DevProfileIdentityClockIT.a13_7_inTheDevProfileIdentityRunsOnTheRealTimeAndPublicResponsesKeepTheFixtureClock` `RealDatabaseIT.a13_3_and_13_7_anUnmarkedDatabaseTakesNoFictionalDataAndNoDevelopmentAccount` `DevelopmentDataIsolationIT.a13_7_theDevelopmentSeedRefusesItTooAndRefusesADatabaseElsewhere` `IdentitySecurityUnitTest.a13_7_developmentAccountsAreOnlyForADatabaseOnThisMachine` Present and correct on a disposable database in the `dev` profile (`DevProfileHttpIT`); absent outside `dev` (`RealDatabaseIT`); refuses an unmarked database and a database that is not on this machine (`DevelopmentDataIsolationIT`, unit test). That the generated passwords are printed to the console and exist in no file is by reading the code. `DevProfileIdentityClockIT` (first real-browser run, finding 5) starts the `dev` profile with fixtures loaded and shows that a staff member enrolls and signs in again with authenticator codes for the real time, that session deadlines, pending sign-ins and rate-limit windows are on the real time, and that the public responses stay byte for byte on the fixture clock. |

## 14. Precedence

| # | Scenario | Status | Executable tests and notes |
|---|---|---|---|
| 14.1 | `POST /auth/change-password`: cookie of a signed-out session and that session's token | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.2 | The same with the cookie of an expired session whose row has not been cleaned up yet | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.3 | The same with the pre-rotation cookie and the pre-rotation token, after a password change elsewhere | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.4 | A live session's cookie with the token of its previous identifier | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.5 | A live session's cookie with another browser's valid token | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.6 | A stale cookie and no token at all | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.7 | No cookie, and a token that was valid for some session | Backend: covered | `PrecedenceIT.a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck` |
| 14.8 | A fresh anonymous session and its valid token, to `POST /auth/change-password`, `/auth/mfa/verify`, `/auth/mfa/enroll`, `/auth/mfa/confirm`, `/auth/mfa/recovery-codes` | Backend: covered | `PrecedenceIT.a14_8_to_14_10_aFreshAnonymousSessionWithItsValidToken` |
| 14.9 | A fresh anonymous session and its valid token, to `POST /auth/login` with correct credentials | Backend: covered | `PrecedenceIT.a14_8_to_14_10_aFreshAnonymousSessionWithItsValidToken` |
| 14.10 | The same to `POST /auth/logout` | Backend: covered | `PrecedenceIT.a14_8_to_14_10_aFreshAnonymousSessionWithItsValidToken` |
| 14.11 | After 14.1: the frontend's follow-up | Browser only: joint integration | Needs the real frontend in a real browser: joint integration. |
| 14.12 | Account disabled, session rows deleted: `GET /me`; a `POST` with the old token | Backend: covered | `PrecedenceIT.a14_12_aDisabledAccountWhoseSessionRowsWereDeleted` |
| 14.13 | Security version raised, session row and token survived: `POST /auth/change-password` with a wrong current password | Backend: covered | `PrecedenceIT.a14_13_and_14_14_aRevokedIdentityWhoseRowSurvivedNeverReachesThePasswordCheck` |
| 14.14 | The same with the correct current password | Backend: covered | `PrecedenceIT.a14_13_and_14_14_aRevokedIdentityWhoseRowSurvivedNeverReachesThePasswordCheck` |
| 14.15 | The same situation: `POST /auth/mfa/recovery-codes` with a wrong code | Backend: covered | `PrecedenceIT.a14_15_theSameForRecoveryCodes` |
| 14.16 | A pending session whose account was disabled meanwhile, row survived: `POST /auth/mfa/verify` with a correct code | Backend: covered | `PrecedenceIT.a14_16_aPendingSessionOfADisabledAccountDoesNotConsumeTheCode` |
| 14.17 | A revoked identity, row survived, calling `POST /auth/login` | Backend: covered | `PrecedenceIT.a14_17_aRevokedIdentityCallingSignInIsTreatedAsAnonymous` |
| 14.18 | Unknown path, wrong origin, bad token, oversized body | Backend: covered | `PrecedenceIT.a14_18_to_14_22_theEarlyChecksInOrder` |
| 14.19 | `PUT /api/auth/login`, wrong origin, bad token | Backend: covered | `PrecedenceIT.a14_18_to_14_22_theEarlyChecksInOrder` |
| 14.20 | `POST /auth/login`: another site's `Origin`, a 1 MiB form-encoded body, no token, no session | Backend: covered | `PrecedenceIT.a14_18_to_14_22_theEarlyChecksInOrder` |
| 14.21 | `POST /auth/login`: own origin, 1 MiB body, wrong content type, no token | Backend: covered | `PrecedenceIT.a14_18_to_14_22_theEarlyChecksInOrder` |
| 14.22 | `POST /auth/login`: own origin, small body, `text/plain`, no token | Backend: covered | `PrecedenceIT.a14_18_to_14_22_theEarlyChecksInOrder` |
| 14.23 | `POST /auth/login`: own origin, JSON, no token, client address already rate-limited | Backend: covered | `PrecedenceIT.a14_23_to_14_25_tokenThenRateLimitThenState` |
| 14.24 | `POST /auth/login`: valid token, client address rate-limited, session fully signed in, malformed body | Backend: covered | `PrecedenceIT.a14_23_to_14_25_tokenThenRateLimitThenState` |
| 14.25 | `POST /auth/login`: valid token, not rate-limited, session fully signed in, malformed body, wrong password | Backend: covered | `PrecedenceIT.a14_23_to_14_25_tokenThenRateLimitThenState` |
| 14.26 | `POST /auth/mfa/verify`: valid token of an anonymous session, malformed body | Backend: covered | `PrecedenceIT.a14_26_and_14_27_stateBeforeValidationAndValidationBeforeTheCode` |
| 14.27 | `POST /auth/mfa/verify`: valid token, pending session, a code of the wrong shape | Backend: covered | `PrecedenceIT.a14_26_and_14_27_stateBeforeValidationAndValidationBeforeTheCode` |
| 14.28 | `POST /auth/change-password`: valid token, signed in, new password too short and current password wrong | Backend: covered | `PrecedenceIT.a14_28_and_14_29_validationBeforeThePasswordAndThePasswordCountedOnce` |
| 14.29 | `POST /auth/change-password`: valid token, signed in, valid body, wrong current password | Backend: covered | `PrecedenceIT.a14_28_and_14_29_validationBeforeThePasswordAndThePasswordCountedOnce` |
| 14.30 | `POST /auth/register`: valid token, sign-up closed, malformed body | Backend: covered | `RegistrationClosedIT.a14_30_theClosedSwitchIsDecidedBeforeTheBodyIsValidated` |
| 14.31 | `POST /auth/logout` with a non-empty body and a bad token | Backend: covered | `PrecedenceIT.a14_31_and_14_32` |
| 14.32 | `POST /auth/mfa/enroll`: valid token, session in `mfa_required` | Backend: covered | `PrecedenceIT.a14_31_and_14_32` |

## What is still owed, and by whom

Not claimed by this document:

- **Browser scenarios.** Every row marked "Browser only" or "Browser repeat" above. They need the real frontend, built for production with its content policy, driven by a real browser against this backend. A first joint run was made on 2026-11-25 on the integration branch (`docs/m1b-acceptance-evidence.md` there); it found four backend defects, corrected in the commit that contains this file, and has to be repeated on the corrected backend. Its results are not restated or claimed here.
- **Independent security verification** of the backend and of the joint system.
- **Manual operator verification**, which no automated test replaces:
  - 7.19: run each operator command on the deployment machine and inspect the process list and the shell history for the credential it printed. The tests show only that the command takes its name as its single argument and asks for the rest on the terminal.
  - 7.20: that the operator follows the identity-check procedure. The command states it, refuses without an answer and records the answer.
  - Deployment: the two keys generated, stored outside source control and backed up; `APV_ALLOWED_ORIGINS` set behind the TLS proxy and `APV_TRUSTED_PROXIES` set to that proxy; Argon2id cost calibrated on the production machine; the breached-password corpus downloaded, built, verified and its SHA-256 recorded (`docs/backend-password-blocklist.md`), since the tests use a synthetic list of the required size and never the real corpus; first super administrator created, activated and enrolled.
- **5.7** stays partly covered until a staff-only and a super-admin-only business route exist.
- **Contract questions** raised by the implementation are listed in `docs/adr/0002-identity-and-sessions.md` under "Corrective review".
