import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/Layout';
import { MissingState, RouteErrorState } from './components/States';
import { CasePage } from './pages/CasePage';
import { CaseDirectoryPage } from './pages/CaseDirectoryPage';
import { HomePage } from './pages/HomePage';
import { IdentityRoutes, SignInPage, SignUpPage, ChallengePage, AccountPage, EnrollmentPreparationPage } from './identity/IdentityPages';

const router = createBrowserRouter([
  { element: <Layout />, errorElement: <RouteErrorState />, children: [
    { path: '/', element: <HomePage /> },
    { path: '/cases', element: <CaseDirectoryPage /> },
    { path: '/cases/:code', element: <CasePage /> },
    { element: <IdentityRoutes />, children: [
      { path: '/sign-in', element: <SignInPage /> },
      { path: '/sign-up', element: <SignUpPage /> },
      { path: '/auth/mfa', element: <ChallengePage /> },
      { path: '/auth/enroll', element: <EnrollmentPreparationPage /> },
      { path: '/account', element: <AccountPage /> },
    ] },
    { path: '*', element: <MissingState /> },
  ] },
]);

export function App() { return <RouterProvider router={router} />; }
