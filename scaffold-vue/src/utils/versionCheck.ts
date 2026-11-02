/**
 * 前端与后端版本一致性检查。
 *
 * <p>问题背景：镜像升级后构建产物文件名带 hash 会变
 * （index-xxxx.js → index-yyyy.js），而已打开的浏览器页面仍持有旧产物。
 * 此时页面壳能跑，但动态 import 的页面 chunk 会 404，
 * 表现为「登录接口成功、却进不去系统」。
 *
 * <p>解决：启动时向后端 /api/health 询问当前版本，与本页构建版本比对；
 * 不一致就刷新一次页面，强制取回新的 index.html 与资源。
 * 用 sessionStorage 打标防止刷新死循环。
 */

import { APP } from '@/data'

const CHECK_FLAG = 'scaffold.version-checked'
const HEALTH_URL = '/api/health'

/** 本页构建时的版本号（如 v1.1.0） */
export function currentVersion(): string {
  return APP.version
}

/**
 * 校验前后端版本是否一致；不一致时刷新页面。
 * @returns true 表示已触发刷新（调用方应中止后续流程）
 */
export async function ensureVersionMatch(): Promise<boolean> {
  try {
    if (sessionStorage.getItem(CHECK_FLAG) === '1') return false
  } catch {
    // 隐私模式下 sessionStorage 不可用：跳过校验，不影响正常使用
    return false
  }

  try {
    const response = await fetch(HEALTH_URL, { headers: { Accept: 'application/json' } })
    if (!response.ok) return false
    const payload = await response.json() as { data?: { version?: string } }
    const serverVersion = payload?.data?.version
    if (!serverVersion || serverVersion === currentVersion()) {
      markChecked()
      return false
    }
    // 版本不一致：标记后刷新，重新加载新版资源
    markChecked()
    location.reload()
    return true
  } catch {
    // 网络异常不阻断登录流程
    return false
  }
}

function markChecked() {
  try {
    sessionStorage.setItem(CHECK_FLAG, '1')
  } catch {
    /* 忽略 */
  }
}
