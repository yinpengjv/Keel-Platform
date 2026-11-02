/// <reference types="vite/client" />

/**
 * 应用版本号，由 vite.config.ts 从项目根的 VERSION 文件注入（形如 v1.1.0）。
 * 与后端 /api/health 回显的版本、镜像 tag 同源。
 */
declare const __APP_VERSION__: string

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
