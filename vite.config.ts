import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Keep illustrations independently cacheable instead of embedding SVGs in JS.
    assetsInlineLimit: (filePath) => filePath.endsWith('.svg') ? false : undefined,
  },
});
