/**
 * 令牌存储：localStorage 为持久层，模块内变量为内存缓存。
 *
 * 独立于 Pinia Store，避免 request -> store -> api -> request 的循环依赖。
 */
const ACCESS_TOKEN_KEY = 'storyforge.accessToken'
const REFRESH_TOKEN_KEY = 'storyforge.refreshToken'

let accessToken: string | null = localStorage.getItem(ACCESS_TOKEN_KEY)
let refreshToken: string | null = localStorage.getItem(REFRESH_TOKEN_KEY)

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return refreshToken
}

export function setTokens(access: string | null, refresh: string | null): void {
  accessToken = access
  refreshToken = refresh
  if (access) {
    localStorage.setItem(ACCESS_TOKEN_KEY, access)
  } else {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
  }
  if (refresh) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
  } else {
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  }
}

export function clearTokens(): void {
  setTokens(null, null)
}

export function hasToken(): boolean {
  return !!accessToken
}
