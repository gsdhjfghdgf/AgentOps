import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base './' 是 Electron 通过 file:// 加载 dist/index.html 的必要条件
export default defineConfig({
  base: './',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
  },
})
