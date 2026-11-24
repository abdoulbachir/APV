import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/Layout';
import { MissingState, RouteErrorState } from './components/States';
import { CasePage } from './pages/CasePage';
import { CaseDirectoryPage } from './pages/CaseDirectoryPage';
import { HomePage } from './pages/HomePage';

const router = createBrowserRouter([
  { element: <Layout />, errorElement: <RouteErrorState />, children: [
    { path: '/', element: <HomePage /> },
    { path: '/cases', element: <CaseDirectoryPage /> },
    { path: '/cases/:code', element: <CasePage /> },
    { path: '*', element: <MissingState /> },
  ] },
]);

export function App() { return <RouterProvider router={router} />; }
