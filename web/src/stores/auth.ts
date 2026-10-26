import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  changePassword as changePasswordApi,
  fetchCurrentUser,
  login as loginApi,
  logout as logoutApi,
  type LoginPayload,
  type UserRole,
  type UserVO
} from '@/api/auth'
import { clearTokens, getRefreshToken, setTokens } from '@/utils/auth-storage'

/**
 * 登录态 Store。
 *
 * 令牌本身由 utils/auth-storage 持有，Store 只维护用户信息与派生状态。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserVO | null>(null)

  const role = computed<UserRole | null>(() => user.value?.role ?? null)
  const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  async function login(payload: LoginPayload) {
    const result = await loginApi(payload)
    setTokens(result.accessToken, result.refreshToken)
    user.value = result.user
    return result.user
  }

  async function loadCurrentUser() {
    user.value = await fetchCurrentUser()
    return user.value
  }

  async function changePassword(oldPassword: string, newPassword: string) {
    await changePasswordApi({ oldPassword, newPassword })
    if (user.value) {
      user.value.mustChangePassword = false
    }
  }

  async function logout() {
    const refresh = getRefreshToken()
    try {
      await logoutApi(refresh)
    } catch {
      // 令牌可能已过期，忽略登出接口异常，仍清理本地状态
    }
    clearTokens()
    user.value = null
  }

  function reset() {
    clearTokens()
    user.value = null
  }

  return {
    user,
    role,
    isSuperAdmin,
    mustChangePassword,
    displayName,
    login,
    loadCurrentUser,
    changePassword,
    logout,
    reset
  }
})
