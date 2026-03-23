import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    emptyOutDir: true,
    sourcemap: false,
    minify: 'esbuild',
    rollupOptions: {
      output: {
        manualChunks: {
          // vue 拆成独立 chunk，命中缓存
          vue: ['vue'],
        },
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/index-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
    reportCompressedSize: true,
    chunkSizeWarningLimit: 500,
  },
  server: {
    port: 5173,
  },
})
