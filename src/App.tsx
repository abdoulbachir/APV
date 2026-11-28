import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/Layout';
import { MissingState, RouteErrorState } from './components/States';
import { CasePage } from './pages/CasePage';
import { CaseDirectoryPage } from './pages/CaseDirectoryPage';
import { HomePage } from './pages/HomePage';
import { IdentityRoutes, SignInPage, SignUpPage, ChallengePage, AccountPage, EnrollmentPreparationPage, SetPasswordPage, ChangePasswordPage, RecoveryCodesPage } from './identity/IdentityPages';
import { GivingPage, StatusPage } from './donations/DonationPages';
import { ReviewQueuePage, GiftPage, ManualEntryPage } from './donations/AdminDonationPages';

const router = createBrowserRouter([
  { element: <Layout />, errorElement: <RouteErrorState />, children: [
    { path: '/', element: <HomePage /> },
    { path: '/cases', element: <CaseDirectoryPage /> },
    { path: '/cases/:code', element: <CasePage /> },
    { path: '/give', element: <GivingPage /> },
    { path: '/track', element: <StatusPage /> },
    { element: <IdentityRoutes />, children: [
      { path: '/sign-in', element: <SignInPage /> },
      { path: '/sign-up', element: <SignUpPage /> },
      { path: '/auth/mfa', element: <ChallengePage /> },
      { path: '/auth/enroll', element: <EnrollmentPreparationPage /> },
      { path: '/account', element: <AccountPage /> },
      { path: '/auth/set-password', element: <SetPasswordPage /> },
      { path: '/account/password', element: <ChangePasswordPage /> },
      { path: '/account/recovery-codes', element: <RecoveryCodesPage /> },
      { path: '/admin/donations', element: <ReviewQueuePage /> },
      { path: '/admin/donations/new', element: <ManualEntryPage /> },
      { path: '/admin/donations/:donationId', element: <GiftPage /> },
    ] },
    { path: '*', element: <MissingState /> },
  ] },
]);

export function App() { return <RouterProvider router={router} />; }
