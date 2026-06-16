import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '../api/auth'

const TOKEN_KEY = 'sunshine-token'

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

  async function login(username: string, password: string) {
    const res = await authApi.login(username, password)
    setToken(res.token)
    user.value = {
      userId: res.userId,
      username: res.username,
      nickname: res.nickname,
    }
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
      user.value = await authApi.me()
      initialized.value = true
      return true
    } catch {
      clearAuth()
      initialized.value = true
      return false
    }
  }

  async function updateProfile(nickname: string) {
    user.value = await authApi.updateProfile(nickname)
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

export function apiHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}
