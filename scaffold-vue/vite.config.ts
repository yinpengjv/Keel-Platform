import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    open: true,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      // 接口文档（Swagger/Knife4j）：开发模式前端与后端不同源，这些路径由 Vite 转发到后端，
      // 让 iframe 里打开的文档与页面同源，票据会话 Cookie 才能正常带上
      '/swagger-ui': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/v3/api-docs': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/doc.html': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/webjars': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
})
