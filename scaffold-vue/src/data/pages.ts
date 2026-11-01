import type { Component } from 'vue'

/* ============================================================
   本站页面的注册清单。
   sys_menu.open_type = page 时，component 只能取这里的 key；
   新增独立页面必须在此登记，否则菜单会落到「页面未绑定」占位页。
   ============================================================ */

export interface RegisteredPage {
  key: string
  label: string
  load: () => Promise<{ default: Component }>
}

export const REGISTERED_PAGES: RegisteredPage[] = [
  { key: 'dashboard/index', label: '工作台', load: () => import('@/views/Dashboard.vue') },
  { key: 'system/user/index', label: '用户管理', load: () => import('@/views/system/Users.vue') },
  { key: 'system/role/index', label: '角色管理', load: () => import('@/views/system/Roles.vue') },
  { key: 'system/menu/index', label: '菜单管理', load: () => import('@/views/system/Menus.vue') },
  { key: 'system/dept/index', label: '部门管理', load: () => import('@/views/system/Depts.vue') },
  { key: 'system/post/index', label: '岗位管理', load: () => import('@/views/system/Posts.vue') },
  { key: 'system/dict/index', label: '字典管理', load: () => import('@/views/system/Dict.vue') },
  { key: 'system/config/index', label: '参数配置', load: () => import('@/views/system/Configs.vue') },
  { key: 'monitor/operlog/index', label: '操作日志', load: () => import('@/views/monitor/OperLog.vue') },
  { key: 'monitor/loginlog/index', label: '登录日志', load: () => import('@/views/monitor/LoginLog.vue') },
  { key: 'tool/gen/index', label: '代码生成', load: () => import('@/views/tool/Generator.vue') },
]

/** 菜单表单的「本站页面」下拉选项 */
export const PAGE_OPTIONS = REGISTERED_PAGES.map(page => ({
  label: page.label,
  value: page.key,
  hint: page.key,
}))

/** 组件标识 → 加载器 */
export const PAGE_LOADERS: Record<string, () => Promise<{ default: Component }>> =
  Object.fromEntries(REGISTERED_PAGES.map(page => [page.key, page.load]))

/** 组件标识 → 中文名，用于列表展示 */
export const PAGE_LABELS: Record<string, string> =
  Object.fromEntries(REGISTERED_PAGES.map(page => [page.key, page.label]))
