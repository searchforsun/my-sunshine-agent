import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '../api/auth'
import { syncTenantFromAuth } from '../composables/useTenantPreference'
import { syncKbDefaultFromAuth } from '../composables/useKbPreference'
import { syncWriteHitlDefaultFromAuth } from '../composables/useWriteHitlMode'
import { isWriteHitlMode } from '../api/writeHitlModes'
import { normalizeSidebarSectionsLayout } from '../api/sidebarSectionsLayouts'

const TOKEN_KEY = 'sunshine-token'

function toAuthUser(res: authApi.AuthUser): authApi.AuthUser {
  return {
    userId: res.userId,
    username: res.username,
    nickname: res.nickname,
    tenantId: res.tenantId || 'default',
    defaultWriteHitlMode: isWriteHitlMode(res.defaultWriteHitlMode)
      ? res.defaultWriteHitlMode
      : 'never',
    sidebarSectionsLayout: normalizeSidebarSectionsLayout(res.sidebarSectionsLayout),
    defaultKbId: res.defaultKbId?.trim() || null,
    personalRules: res.personalRules ?? null,
    githubUrl: res.githubUrl ?? null,
    githubToken: res.githubToken ?? null,
    githubTokenSet: res.githubTokenSet ?? !!res.githubToken,
    gitlabUrl: res.gitlabUrl ?? null,
    gitlabToken: res.gitlabToken ?? null,
    gitlabTokenSet: res.gitlabTokenSet ?? !!res.gitlabToken,
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const user = ref<authApi.AuthUser | null>(null)
  const initialized = ref(false)

  const isLoggedIn = computed(() => !!token.value)

  function setToken(value: string | null) {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
    localStorage.removeItem('sunshine-user-id')
  }

  function applyUser(next: authApi.AuthUser) {
    user.value = toAuthUser(next)
    syncTenantFromAuth(user.value.tenantId)
    syncWriteHitlDefaultFromAuth(user.value.defaultWriteHitlMode)
    syncKbDefaultFromAuth(user.value.defaultKbId)
  }

  async function login(username: string, password: string) {
    const res = await authApi.login(username, password)
    setToken(res.token)
    applyUser(res)
  }

  async function register(username: string, password: string, nickname?: string) {
    await authApi.register(username, password, nickname)
  }

  async function fetchMe() {
    if (!token.value) {
      user.value = null
      initialized.value = true
      return false
    }
    try {
      applyUser(await authApi.me())
      initialized.value = true
      return true
    } catch {
      clearAuth()
      initialized.value = true
      return false
    }
  }

  async function updateProfile(
    nickname: string,
    tenantId: string,
    defaultWriteHitlMode?: string,
    personalRules?: string | null,
    githubUrl?: string | null,
    githubToken?: string | null,
    gitlabUrl?: string | null,
    gitlabToken?: string | null,
    sidebarSectionsLayout?: string,
    defaultKbId?: string | null,
  ) {
    const res = await authApi.updateProfile(nickname, tenantId, defaultWriteHitlMode, personalRules,
      githubUrl, githubToken, gitlabUrl, gitlabToken, sidebarSectionsLayout, defaultKbId)
    if (!res.token?.trim()) {
      throw new Error('资料已保存但登录凭证刷新失败，请重新登录')
    }
    setToken(res.token)
    applyUser(res)
  }

  async function logout() {
    try {
      if (token.value) {
        await authApi.logout()
      }
    } finally {
      clearAuth()
    }
  }

  function clearAuth() {
    setToken(null)
    user.value = null
  }

  return {
    token,
    user,
    initialized,
    isLoggedIn,
    login,
    register,
    fetchMe,
    updateProfile,
    logout,
    clearAuth,
  }
})

export function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

export function apiHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' }
}
