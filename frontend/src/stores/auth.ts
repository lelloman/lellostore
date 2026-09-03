import { defineStore } from 'pinia'
import { ref, computed, onScopeDispose } from 'vue'
import { authService, type CurrentIdentity, type User } from '@/services/auth'

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)
  const backendIdentity = ref<CurrentIdentity | null>(null)

  // Getters
  const isAuthenticated = computed(() => !!user.value && !user.value.expired)
  const accessToken = computed(() => user.value?.access_token ?? null)
  const userProfile = computed(() => user.value?.profile ?? null)

  const userRoles = computed(() => {
    const profile = user.value?.profile as Record<string, unknown> | undefined
    if (!profile) return []

    const claimPath = import.meta.env.VITE_OIDC_ROLE_CLAIM_PATH || 'realm_access.roles'
    let claim: unknown = profile
    for (const segment of claimPath.split('.')) {
      if (!claim || typeof claim !== 'object') return []
      claim = (claim as Record<string, unknown>)[segment]
    }

    if (typeof claim === 'string') return [claim]
    if (!Array.isArray(claim)) return []
    return claim.filter((role): role is string => typeof role === 'string')
  })

  const isAdmin = computed(() => {
    if (backendIdentity.value) return backendIdentity.value.is_admin
    const adminRole = import.meta.env.VITE_OIDC_ADMIN_ROLE || 'admin'
    return userRoles.value.includes(adminRole)
  })

  async function syncBackendIdentity() {
    backendIdentity.value = null
    const accessToken = user.value?.access_token
    if (!accessToken || user.value?.expired) {
      return
    }
    backendIdentity.value = await authService.getCurrentIdentity(accessToken)
  }

  function applyUser(newUser: User) {
    user.value = newUser
    if (!isLoading.value) {
      void syncBackendIdentity().catch((e) => {
        console.error('Backend identity refresh error:', e)
      })
    }
  }

  function clearUser() {
    user.value = null
    backendIdentity.value = null
  }

  const removeUserLoadedListener = authService.onUserLoaded(applyUser)
  const removeUserUnloadedListener = authService.onUserUnloaded(clearUser)
  onScopeDispose(() => {
    removeUserLoadedListener()
    removeUserUnloadedListener()
  })

  // Actions
  async function initialize() {
    isLoading.value = true
    error.value = null
    try {
      const storedUser = await authService.getUser()
      user.value = storedUser
      if (storedUser?.expired) {
        user.value = await authService.silentRenew()
        if (!user.value) {
          await authService.clearLocalSession()
        }
      }
      await syncBackendIdentity()
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
      await syncBackendIdentity()
    } catch (e) {
      error.value = 'Authentication callback failed'
      console.error('Callback error:', e)
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function handleLogoutCallback() {
    isLoading.value = true
    error.value = null
    try {
      await authService.handleLogoutCallback()
      clearUser()
    } catch (e) {
      error.value = 'Logout callback failed'
      console.error('Logout callback error:', e)
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function logout() {
    error.value = null
    try {
      await authService.logout()
      clearUser()
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
        await syncBackendIdentity()
      }
    } catch (e) {
      console.error('Token refresh error:', e)
    }
  }

  async function clearSession() {
    await authService.clearLocalSession()
    clearUser()
  }

  function setUser(newUser: User) {
    user.value = newUser
  }

  return {
    // State
    user,
    backendIdentity,
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
    handleLogoutCallback,
    logout,
    clearSession,
    refreshToken,
    setUser,
  }
})
