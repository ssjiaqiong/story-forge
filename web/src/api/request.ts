import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '@/utils/auth-storage'

/** 后端统一响应体 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
}

/** 统一业务异常 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

/** 错误码常量（与后端 ErrorCode 对齐） */
export const ErrorCodes = {
  SUCCESS: 0,
  UNAUTHORIZED: 20001,
  BAD_CREDENTIALS: 20002,
  USER_DISABLED: 20003,
  MUST_CHANGE_PASSWORD: 20004,
  FORBIDDEN: 20006
} as const

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000
})

/** 刷新令牌并发去重 */
let refreshing: Promise<boolean> | null = null

const bareHttp = axios.create({ baseURL: '/api', timeout: 30000 })

async function refreshAccessToken(): Promise<boolean> {
  const token = getRefreshToken()
  if (!token) {
    return false
  }
  if (!refreshing) {
    refreshing = bareHttp
      .post<ApiResult<{ accessToken: string; refreshToken: string }>>('/auth/refresh', { refreshToken: token })
      .then((response) => {
        const body = response.data
        if (body.code === 0 && body.data) {
          setTokens(body.data.accessToken, body.data.refreshToken)
          return true
        }
        return false
      })
      .catch(() => false)
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult>) => {
    const response = error.response
    const body = response?.data
    const status = response?.status
    const code = body?.code

    const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined
    const isAuthExpired = status === 401 && (code === ErrorCodes.UNAUTHORIZED || code === undefined)

    if (isAuthExpired && original && !original._retried) {
      const ok = await refreshAccessToken()
      if (ok) {
        original._retried = true
        original.headers = original.headers ?? {}
        ;(original.headers as Record<string, string>).Authorization = `Bearer ${getAccessToken()}`
        return http.request(original)
      }
      clearTokens()
      if (window.location.pathname !== '/login') {
        window.location.href = `/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`
      }
    }

    throw new ApiError(code ?? status ?? -1, body?.message || error.message || '网络异常')
  }
)

/**
 * 发起请求并解包统一响应体。
 *
 * @throws {ApiError} 业务失败或网络异常
 */
export async function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  const body = response.data
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message)
  }
  return body.data
}

export const api = {
  get: <T = unknown>(url: string, params?: unknown, timeout?: number, signal?: AbortSignal) =>
    request<T>({ url, method: 'GET', params, timeout, signal }),
  post: <T = unknown>(url: string, data?: unknown, timeout?: number, signal?: AbortSignal) =>
    request<T>({ url, method: 'POST', data, timeout, signal }),
  put: <T = unknown>(url: string, data?: unknown, timeout?: number, signal?: AbortSignal) =>
    request<T>({ url, method: 'PUT', data, timeout, signal }),
  delete: <T = unknown>(url: string, timeout?: number, signal?: AbortSignal) =>
    request<T>({ url, method: 'DELETE', timeout, signal })
}

export default api
