import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, AUTH_STORAGE_KEY } from '@/lib/api'
import type { AuthSession, LoginRequest } from '@/types/api'

function loadSession() {
  for (const storage of [localStorage, sessionStorage]) {
    const raw = storage.getItem(AUTH_STORAGE_KEY)
    if (!raw) continue
    try {
      return JSON.parse(raw) as AuthSession
    } catch {
      storage.removeItem(AUTH_STORAGE_KEY)
    }
  }
  return null
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(loadSession())
  const isAuthenticated = computed(() => {
    if (!session.value) return false
    return new Date(session.value.expiresAt).getTime() > Date.now()
  })
  const canEdit = computed(
    () => session.value?.role === 'ADMIN' || session.value?.role === 'EDITOR',
  )
  const isAdmin = computed(() => session.value?.role === 'ADMIN')

  function persistSession(value: AuthSession, remember?: boolean) {
    session.value = value
    const useLocal = remember ?? localStorage.getItem(AUTH_STORAGE_KEY) !== null
    const target = useLocal ? localStorage : sessionStorage
    const other = useLocal ? sessionStorage : localStorage
    other.removeItem(AUTH_STORAGE_KEY)
    target.setItem(AUTH_STORAGE_KEY, JSON.stringify(value))
  }

  async function login(credentials: LoginRequest, remember = true) {
    const response = await api.post<AuthSession>('/api/v1/auth/login', credentials, {
      authenticated: false,
    })
    persistSession(response, remember)
    return response
  }

  function logout() {
    session.value = null
    localStorage.removeItem(AUTH_STORAGE_KEY)
    sessionStorage.removeItem(AUTH_STORAGE_KEY)
  }

  return {
    session,
    isAuthenticated,
    canEdit,
    isAdmin,
    login,
    persistSession,
    logout,
  }
})
