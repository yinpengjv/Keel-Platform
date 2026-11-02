/**
 * 静态资源加载失败的兜底处理。
 *
 * <p>场景：镜像升级后构建产物的文件名带 hash 会变（index-xxxx.js），
 * 而用户浏览器里还开着升级前加载的旧页面。登录成功后前端要懒加载新的
 * 页面 chunk（如 Dashboard-xxx.js），该文件在新镜像中已不存在：
 *
 * <ul>
 *   <li>后端已改为对不存在的构建产物返回 404，不再回落成 index.html；</li>
 *   <li>但浏览器的动态 import() 仍会失败，抛出「Failed to fetch dynamically
 *       imported module」这类非业务错误，前端若无感知，用户只会看到
 *       「登录失败」这类误导性提示。</li>
 * </ul>
 *
 * <p>这里识别该类错误并自动刷新一次，拿到新版 index.html 后即可正常进入。
 * 用 sessionStorage 打标防止刷新死循环。
 */

const RELOAD_FLAG = 'scaffold.asset-reload'

/** 判断异常是否由「动态 import 加载 chunk 失败」引起 */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '')
  if (!message) return false
  // ① 请求失败（后端已对缺失构建产物返回 404）
  if (/Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Cannot find module/i.test(message)) {
    return true
  }
  // ② 拿到非 JS 内容（早期版本后端会把缺失产物回落到 index.html，浏览器按 MIME 报错）
  if (/Failed to load module script|MIME type|Unexpected token '<'|is not a valid JavaScript module/i.test(message)) {
    return true
  }
  return false
}

/**
 * 触发一次强制刷新以加载新版静态资源。
 * 同一会话只自动刷新一次；第二次仍失败则交由调用方提示用户手动刷新。
 */
export function reloadForNewAssets(): boolean {
  try {
    if (sessionStorage.getItem(RELOAD_FLAG) === '1') return false
    sessionStorage.setItem(RELOAD_FLAG, '1')
  } catch {
    return false
  }
  location.reload()
  return true
}

/** 静态资源失效时的统一处理：能自动刷新就刷新，否则提示用户手动刷新 */
export function handleAssetReloadError(error: unknown): boolean {
  if (!isChunkLoadError(error)) return false
  return reloadForNewAssets()
}
