import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { I18nProvider } from './i18n/I18nContext';
import '@fontsource-variable/manrope';
import '@fontsource/syne/600.css';
import '@fontsource/syne/700.css';
import './styles/tokens.css';
import './styles/global.css';
import './styles/directory.css';

createRoot(document.getElementById('root')!).render(<StrictMode><I18nProvider><App /></I18nProvider></StrictMode>);
