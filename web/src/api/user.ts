import api from '@/api/request'
import type { UserRole, UserStatus, UserVO } from '@/api/auth'

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

export interface UserQuery {
  page?: number
  size?: number
  keyword?: string
  role?: UserRole | ''
  status?: UserStatus | ''
  includeDeleted?: boolean
}

export interface UserCreatePayload {
  username: string
  nickname?: string
  role: UserRole
  initialPassword?: string
  remark?: string
}

export interface UserUpdatePayload {
  nickname?: string
  role?: UserRole
  remark?: string
}

export function pageUsers(query: UserQuery) {
  const params: Record<string, unknown> = {
    page: query.page ?? 1,
    size: query.size ?? 20,
    includeDeleted: query.includeDeleted ?? false
  }
  if (query.keyword) params.keyword = query.keyword
  if (query.role) params.role = query.role
  if (query.status) params.status = query.status
  return api.get<PageResult<UserVO>>('/admin/users', params)
}

export function getUser(id: string) {
  return api.get<UserVO>(`/admin/users/${id}`)
}

export function createUser(payload: UserCreatePayload) {
  return api.post<UserVO>('/admin/users', payload)
}

export function updateUser(id: string, payload: UserUpdatePayload) {
  return api.put<UserVO>(`/admin/users/${id}`, payload)
}

export function changeUserStatus(id: string, status: UserStatus) {
  return api.put<UserVO>(`/admin/users/${id}/status`, { status })
}

export function resetUserPassword(id: string, newPassword: string) {
  return api.put<void>(`/admin/users/${id}/password`, { newPassword })
}

export function deleteUser(id: string) {
  return api.delete<void>(`/admin/users/${id}`)
}

export function restoreUser(id: string) {
  return api.post<UserVO>(`/admin/users/${id}/restore`)
}
