import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // Keep server-side fixtures and backend sources inaccessible even via /@fs or ?raw.
    // Retain Vite's standard sensitive-file deny rules when overriding fs.deny.
    fs: { strict: true, deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/contract/**', '**/backend/**', '**/docs/**'] },
    proxy: { '/api': 'http://127.0.0.1:8080', '/media/public': 'http://127.0.0.1:8080' } },
  preview: { proxy: { '/api': 'http://127.0.0.1:8080', '/media/public': 'http://127.0.0.1:8080' } },
  build: {
    // Keep illustrations independently cacheable instead of embedding SVGs in JS.
    assetsInlineLimit: (filePath) => /\.(svg|webp)$/i.test(filePath) ? false : undefined,
  },
});
