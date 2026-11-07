import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { shira } from './plugins/shira.ts';

export default defineConfig({
  // Relative, so that a copy of the site works from any address.
  base: './',
  plugins: [react(), shira()],
  build: { target: 'es2023', chunkSizeWarningLimit: 1500 },
  test: { environment: 'node', include: ['src/**/*.test.ts', 'plugins/**/*.test.ts'] },
});
