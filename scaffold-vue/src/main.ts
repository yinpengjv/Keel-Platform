import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { installPermissionDirective } from './directives/permission'
import { installHoverLift, installPressFeedback, installRipple } from './composables/motion'
import { isChunkLoadError, reloadForNewAssets } from './utils/assetReload'
import { ensureVersionMatch } from './utils/versionCheck'

// 设计令牌 → 基础 → 材质 → 控件 → 外壳 → 页面
import './styles/tokens.css'
import './styles/base.css'
import './styles/materials.css'
import './styles/components.css'
import './styles/instruments.css'
import './styles/layout.css'
import './styles/pages.css'

// 外观设置在挂载前写入 data-theme / data-density，避免首屏闪色
import './composables/appearance'

const app = createApp(App)
installPermissionDirective(app)
app.use(router).mount('#app')

installPressFeedback()
installHoverLift()
installRipple()

// 镜像升级后浏览器可能仍持有旧产物：启动时校验前后端版本，不一致就刷新取新版
void ensureVersionMatch()

// 静态资源版本不一致（镜像升级后浏览器仍持有旧页面）时，动态 import 会失败。
// 这里统一兜底：识别到该类错误就刷新一次页面取新版资源，避免用户看到
// 「登录成功却进不去」这类无解的报错。
window.addEventListener('error', (event) => {
  const target = event.target as { src?: string } | null
  if (target?.src && /\.(js|css|mjs)$/.test(target.src)) {
    reloadForNewAssets()
    return
  }
  if (isChunkLoadError(event.error)) reloadForNewAssets()
})
window.addEventListener('unhandledrejection', (event) => {
  if (isChunkLoadError(event.reason)) reloadForNewAssets()
})
