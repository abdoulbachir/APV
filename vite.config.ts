import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { documentPolicy, productionHeaders } from './security/content-policy.mjs';

// The backend accepts a state-changing request only when its Origin equals the host the request was
// addressed to. Vite's string shorthand for a proxy target rewrites the Host header to the backend's
// own address (changeOrigin: true), which makes every identity POST from the browser fail with
// 403 forbidden. Forward the browser's Host unchanged, as a same-origin production proxy does.
const backend = { target: 'http://127.0.0.1:8080', changeOrigin: false };
const backendProxy = { '/api': backend, '/media/public': backend };

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  // Dev remains a mock preview by default. A normal build is always real by default.
  // A fictional static design preview must be requested explicitly via build:mock.
  const apiMode = env.VITE_API_MODE ?? (command === 'build' && mode !== 'mock' ? 'real' : 'mock');
  if (apiMode !== 'mock' && apiMode !== 'real') throw new Error('Invalid VITE_API_MODE');
  if (command === 'build' && mode !== 'mock' && apiMode === 'mock') {
    throw new Error('Use npm run build:mock for an explicitly fictional preview.');
  }
  return {
    define: { 'import.meta.env.VITE_API_MODE': JSON.stringify(apiMode) },
    plugins: [react(), {
      name: 'apv-document-security-policy',
      apply: 'build',
      transformIndexHtml: { order: 'pre', handler: () => [
        { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: documentPolicy }, injectTo: 'head-prepend' },
        { tag: 'meta', attrs: { name: 'referrer', content: 'no-referrer' }, injectTo: 'head-prepend' },
      ] },
    }, {
      name: 'apv-real-build-fixture-boundary',
      apply: 'build',
      generateBundle(_options, bundle) {
        if (apiMode !== 'real') return;
        for (const item of Object.values(bundle)) {
          if (item.type !== 'chunk') continue;
          for (const id of Object.keys(item.modules)) {
            if (/(?:^|[\\/])(mock-client|mock-model)\.ts(?:$|\?)/.test(id) ||
                /(?:^|[\\/])dataset\.public\.json(?:$|\?)/.test(id)) {
              this.error('Fictional fixture module in real build: ' + id);
            }
          }
        }
      },
    }],
    server: {
      // Keep server-side fixtures and backend sources inaccessible even via /@fs or ?raw.
      // Retain Vite's standard sensitive-file deny rules when overriding fs.deny.
      fs: { strict: true, deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/contract/**', '**/backend/**', '**/docs/**', '**/dataset*.tmp'] },
      proxy: backendProxy,
    },
    preview: { headers: productionHeaders, proxy: backendProxy },
    build: {
      // Keep illustrations independently cacheable instead of embedding SVGs in JS.
      assetsInlineLimit: 0,
    },
  };
});
