import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authService, type User } from '@/services/auth'

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)

  // Getters
  const isAuthenticated = computed(() => !!user.value && !user.value.expired)
  const accessToken = computed(() => user.value?.access_token ?? null)
  const userProfile = computed(() => user.value?.profile ?? null)

  const userRoles = computed(() => {
    // Extract roles from token claims
    // Supports Keycloak-style realm_access.roles
    const profile = user.value?.profile as Record<string, unknown> | undefined
    const realmAccess = profile?.realm_access as { roles?: string[] } | undefined
    if (realmAccess?.roles) {
      return realmAccess.roles
    }
    // Also support flat roles array
    const roles = profile?.roles as string[] | undefined
    return roles ?? []
  })

  const isAdmin = computed(() => userRoles.value.includes('admin'))

  // Actions
  async function initialize() {
    isLoading.value = true
    error.value = null
    try {
      user.value = await authService.getUser()
    } catch (e) {
      error.value = 'Failed to initialize authentication'
      console.error('Auth initialization error:', e)
    } finally {
      isLoading.value = false
    }
  }

  async function login() {
    error.value = null
    try {
      await authService.login()
    } catch (e) {
      error.value = 'Login failed'
      console.error('Login error:', e)
    }
  }

  async function handleCallback() {
    isLoading.value = true
    error.value = null
    try {
      user.value = await authService.handleCallback()
    } catch (e) {
      error.value = 'Authentication callback failed'
      console.error('Callback error:', e)
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function logout() {
    error.value = null
    try {
      await authService.logout()
      user.value = null
    } catch (e) {
      error.value = 'Logout failed'
      console.error('Logout error:', e)
    }
  }

  async function refreshToken() {
    try {
      const newUser = await authService.silentRenew()
      if (newUser) {
        user.value = newUser
      }
    } catch (e) {
      console.error('Token refresh error:', e)
    }
  }

  function setUser(newUser: User) {
    user.value = newUser
  }

  return {
    // State
    user,
    isLoading,
    error,
    // Getters
    isAuthenticated,
    accessToken,
    userProfile,
    userRoles,
    isAdmin,
    // Actions
    initialize,
    login,
    handleCallback,
    logout,
    refreshToken,
    setUser,
  }
})
