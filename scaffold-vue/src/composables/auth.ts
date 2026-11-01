import { ref, type Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import { ApiError, authApi, authSession, type CurrentUser, type LoginRequest, type RouteItem } from '@/api'
import { setPermissions } from '@/directives/permission'
import type { NavGroup, NavLeaf } from '@/data'
import { PAGE_LOADERS } from '@/data/pages'
import { clearDictCache } from './dict'
import { clearDeptCache } from './dept'

/* ============================================================
   登录态、权限与动态菜单。
   菜单与路由完全来自后端 /api/auth/routes（sys_menu 表），
   前端只维护「组件路径 → 页面组件」的映射。
   ============================================================ */

export const authed = ref<boolean>(authSession.isAuthenticated())
export const currentUser = ref<CurrentUser | null>(authSession.user() as CurrentUser | null)
export const navGroups = ref<NavGroup[]>([])
export const dynamicRoutes = ref<RouteRecordRaw[]>([])
export const sessionVersion = ref(0)
/** 页面组件标识 → 当前账号可访问的路由路径（如 monitor/operlog/index → /monitor/operlog） */
export const componentPaths = ref<Record<string, string>>({})

/** 当前账号可访问时返回该页面路径，否则 null；跨页跳转统一走这里，避免写死菜单路径 */
export function pathOf(component: string) {
  return componentPaths.value[component] ?? null
}
let resetSessionRoutes: (() => void) | undefined

/** 由路由入口注册，外壳只通过 logout 清理会话，避免循环导入路由。 */
export function setSessionRouteReset(handler: () => void) {
  resetSessionRoutes = handler
}

// 刷新页面后先用本地缓存的权限，随后由 refreshUser() 以服务端为准
setPermissions(currentUser.value?.permissions ?? [])

/** 内嵌网站的承载组件标识：openType=iframe 时统一挂这个组件，地址放在 component 字段 */
const IFRAME_VIEW_KEY = 'builtin/iframe'
/** 未注册组件时的占位页 */
const MISSING_VIEW_KEY = 'builtin/missing'

/**
 * sys_menu.component 的取值必须在前端登记（见 data/pages.ts）；
 * iframe / external 类型的 component 存完整地址，不走这里。
 */
const COMPONENT_MAP: Record<string, () => Promise<{ default: Component }>> = {
  ...PAGE_LOADERS,
  [IFRAME_VIEW_KEY]: () => import('@/views/Iframe.vue'),
  [MISSING_VIEW_KEY]: () => import('@/views/MissingPage.vue'),
}

function isHttpUrl(value?: string) {
  return /^https?:\/\//i.test(value ?? '')
}

/** 是否为「嵌入网站」菜单：地址必须是 http/https，否则视为配置错误并丢弃 */
function isEmbedded(item: RouteItem) {
  return item.openType === 'iframe' && isHttpUrl(item.component)
}

/** 是否为「新标签页打开」菜单 */
function isExternal(item: RouteItem) {
  return item.openType === 'external' && isHttpUrl(item.component)
}


/** 路由名由路径推导：/system/user → system-user */
export function routeNameOf(path: string) {
  return path.replace(/^\/+|\/+$/g, '').replace(/\//g, '-') || 'home'
}

function normalizePath(path?: string) {
  if (!path) return ''
  return path.startsWith('/') ? path : `/${path}`
}

function leafOf(item: RouteItem): NavLeaf | null {
  const path = normalizePath(item.path)
  if (!path || !item.component) return null
  if (item.openType === 'external') {
    if (!isExternal(item)) return null
    return { key: routeNameOf(path), label: item.name, icon: item.icon || 'upload', path, externalUrl: item.component }
  }
  if (item.openType === 'iframe') {
    if (!isEmbedded(item)) return null
    return { key: routeNameOf(path), label: item.name, icon: item.icon || 'monitor', path }
  }
  // 本站页面：组件标识未注册也保留菜单，进页面后由占位页提示，避免整组导航静默消失
  return { key: routeNameOf(path), label: item.name, icon: item.icon || 'file', path }
}

function buildNav(tree: RouteItem[]): NavGroup[] {
  const groups: NavGroup[] = []
  for (const node of tree) {
    const children = node.children ?? []
    if (!children.length) {
      const leaf = leafOf(node)
      if (leaf) groups.push({ key: leaf.key, label: leaf.label, icon: leaf.icon, single: true, items: [leaf] })
      continue
    }
    const items = children.map(leafOf).filter((leaf): leaf is NavLeaf => Boolean(leaf))
    // 目录图标留空时不渲染占位图标，保持纯文字分组标题
    if (items.length) groups.push({ key: `group-${node.id ?? node.name}`, label: node.name, icon: node.icon || '', items })
  }
  return groups
}

function buildRoutes(groups: NavGroup[], tree: RouteItem[]): RouteRecordRaw[] {
  const nodeByPath = new Map<string, RouteItem>()
  const walk = (nodes: RouteItem[]) => nodes.forEach(node => {
    if (node.path) nodeByPath.set(normalizePath(node.path), node)
    walk(node.children ?? [])
  })
  walk(tree)
  return groups.flatMap(group => group.items.flatMap(item => {
    const node = nodeByPath.get(item.path)
    // 新标签页菜单由外部站点承载，不注册站内路由
    if (!node || isExternal(node)) return []
    const embedded = isEmbedded(node)
    const loader = embedded
      ? COMPONENT_MAP[IFRAME_VIEW_KEY]
      : COMPONENT_MAP[node.component ?? ''] ?? COMPONENT_MAP[MISSING_VIEW_KEY]
    const route: RouteRecordRaw = {
      path: item.path.replace(/^\//, ''),
      name: item.key,
      component: loader,
      meta: {
        title: item.label,
        group: group.single ? undefined : group.label,
        component: embedded ? undefined : node.component,
      },
    }
    // 嵌入网站的地址通过 props 传给承载组件，避免写进 meta 影响标题逻辑
    if (embedded) route.props = { url: node.component }
    return [route]
  }))
}

export async function loadRoutes() {
  const version = sessionVersion.value
  const data = await authApi.routes()
  if (version !== sessionVersion.value) return
  const groups = buildNav(data.routes)
  navGroups.value = groups
  dynamicRoutes.value = buildRoutes(groups, data.routes)
  const visible = new Set(groups.flatMap(group => group.items.map(item => item.path)))
  const paths: Record<string, string> = {}
  const walk = (nodes: RouteItem[]) => nodes.forEach(node => {
    const path = normalizePath(node.path)
    const sitePage = node.openType !== 'iframe' && node.openType !== 'external'
    if (node.component && sitePage && visible.has(path)) paths[node.component] = path
    walk(node.children ?? [])
  })
  walk(data.routes)
  componentPaths.value = paths
}

/** 以服务端为准刷新当前用户与权限码 */
export async function refreshUser() {
  const version = sessionVersion.value
  const user = await authApi.me()
  if (version !== sessionVersion.value) return user
  currentUser.value = user
  setPermissions(user.permissions ?? [])
  const token = authSession.token()
  if (token) authSession.save({ token, expiresIn: remainingSeconds() }, user, authSession.isRemembered())
  return user
}

function remainingSeconds() {
  return Math.max(60, Math.round((authSession.expiresAt() - Date.now()) / 1000))
}

export async function login(credentials: LoginRequest, remember: boolean) {
  clearSession()
  const version = sessionVersion.value
  try {
    const loginResponse = await authApi.login(credentials)
    if (version !== sessionVersion.value) throw new Error('登录状态已变化，请重新登录')
    authSession.save(loginResponse, { username: credentials.username, nickname: credentials.username }, remember)
    const user = await authApi.me()
    if (version !== sessionVersion.value) throw new Error('登录状态已变化，请重新登录')
    authSession.save(loginResponse, user, remember)
    currentUser.value = user
    setPermissions(user.permissions ?? [])
    authed.value = true
    return user
  } catch (error) {
    if (version === sessionVersion.value) clearSession()
    throw error
  }
}

export function clearSession() {
  sessionVersion.value += 1
  authed.value = false
  resetSessionRoutes?.()
  currentUser.value = null
  navGroups.value = []
  dynamicRoutes.value = []
  componentPaths.value = {}
  setPermissions([])
  authSession.clear()
  clearDictCache()
  clearDeptCache()
}

export async function logout() {
  // 先捕获旧 token 发出退出请求，再同步清理；慢请求不能留下旧菜单或清掉新会话。
  const task = authSession.isAuthenticated() ? authApi.logout() : Promise.resolve()
  clearSession()
  try {
    await task
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 401)) console.warn('logout failed', error)
  }
}
