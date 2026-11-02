import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

/**
 * 单一版本源：项目根的 VERSION 文件（内容形如 1.1.0）。
 * 前端界面显示、镜像 tag 与后端 /api/health 回显都以它为准，改版本只需改这一处。
 *
 * 候选路径覆盖三种运行场景：本地开发（scaffold-vue/VERSION 的上一级）、
 * Docker 构建（COPY 到 /VERSION）、以及从仓库根执行的构建。
 */
function readAppVersion(): string {
  const candidates = [
    fileURLToPath(new URL('../VERSION', import.meta.url)),
    resolve(process.cwd(), '../VERSION'),
    resolve(process.cwd(), 'VERSION'),
  ]
  for (const candidate of candidates) {
    try {
      const raw = readFileSync(candidate, 'utf-8').trim()
      if (raw) return `v${raw}`
    } catch {
      // 换下一个候选路径
    }
  }
  console.warn('[keel] 未找到 VERSION 文件，版本号降级为 v0.0.0')
  return 'v0.0.0'
}

const appVersion = readAppVersion()

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
  },
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
