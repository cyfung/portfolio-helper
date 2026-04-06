import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig(({ command }) => ({
  plugins: [react()],
  // In production, assets are served under /static/ by Ktor's staticResources("/static","static")
  // In dev, Vite serves from root and proxies /api + /static to the Ktor backend
  base: command === 'build' ? '/static/' : '/',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: '../build/generated/frontend/static',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/index.js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/index[extname]',
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'https://localhost:8443',
        secure: false,
        changeOrigin: true,
      },
      '/static': {
        target: 'https://localhost:8443',
        secure: false,
        changeOrigin: true,
      },
      '/sync': {
        target: 'https://localhost:8443',
        secure: false,
        changeOrigin: true,
      },
    },
  },
}))
