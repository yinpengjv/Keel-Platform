import type { HttpClient } from './http'

export interface LoginRequest {
  username: string
  password: string
  captcha: string
  captchaUuid: string
}

export interface CaptchaResponse {
  uuid: string
  code: string
}

/** 登录前查询验证码要求：首次登录为 false，登录失败后窗口期内为 true */
export interface LoginChallengeResponse {
  captchaRequired: boolean
  windowSeconds: number
  /** 连续失败多少次将锁定账号（0 或未返回表示未启用） */
  maxFailures: number
  /** 锁定时长（分钟） */
  lockMinutes: number
}

export interface LoginResponse {
  token: string
  expiresIn: number
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export interface CurrentUser {
  id?: number
  username: string
  nickname: string
  deptId?: number
  deptName?: string
  roles: string[]
  permissions: string[]
}

export interface RouteItem {
  id?: number
  parentId?: number
  name: string
  routeName?: string
  routePath?: string
  path?: string
  component?: string
  /** 打开方式：page 本站页面（component 为组件标识），iframe 嵌入网站（component 为完整地址） */
  openType?: string
  icon?: string
  permission?: string
  children?: RouteItem[]
}

export interface RoutesResponse {
  routes: RouteItem[]
}

export function createAuthApi(client: HttpClient) {
  return {
    captcha: () => client.get<CaptchaResponse>('/auth/captcha'),
    loginChallenge: (username: string) =>
      client.get<LoginChallengeResponse>('/auth/login-challenge', { username }),
    login: (body: LoginRequest) => client.post<LoginResponse>('/auth/login', body),
    me: () => client.get<CurrentUser>('/auth/me'),
    routes: () => client.get<RoutesResponse>('/auth/routes'),
    changePassword: (body: ChangePasswordRequest) => client.put<void>('/auth/password', body),
    logout: () => client.post<void>('/auth/logout'),
  }
}
