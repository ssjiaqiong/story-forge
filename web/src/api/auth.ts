import api from '@/api/request'

export type UserRole = 'SUPER_ADMIN' | 'USER'
export type UserStatus = 'ACTIVE' | 'DISABLED'

export interface UserVO {
  id: string
  username: string
  nickname?: string
  role: UserRole
  status: UserStatus
  mustChangePassword?: boolean
  remark?: string
  lastLoginAt?: string
  lastLoginIp?: string
  deleted?: number
  createdAt?: string
  updatedAt?: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

export interface LoginPayload {
  username: string
  password: string
}

export interface ChangePasswordPayload {
  oldPassword: string
  newPassword: string
}

export function login(payload: LoginPayload) {
  return api.post<LoginResponse>('/auth/login', payload)
}

export function logout(refreshToken?: string | null) {
  return api.post<void>('/auth/logout', { refreshToken: refreshToken ?? undefined })
}

export function fetchCurrentUser() {
  return api.get<UserVO>('/auth/me')
}

export function changePassword(payload: ChangePasswordPayload) {
  return api.post<void>('/auth/password', payload)
}
