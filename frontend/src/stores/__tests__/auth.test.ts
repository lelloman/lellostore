import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'

vi.mock('@/services/auth', () => ({
  authService: {
    getUser: vi.fn(),
    login: vi.fn(),
    handleCallback: vi.fn(),
    handleLogoutCallback: vi.fn(),
    logout: vi.fn(),
    silentRenew: vi.fn(),
  },
}))

describe('Auth Store authorization', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubEnv('VITE_OIDC_ADMIN_ROLE', 'store-manager')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('uses the configured admin role', () => {
    const store = useAuthStore()
    store.setUser({
      expired: false,
      profile: {
        sub: 'manager-user',
        realm_access: { roles: ['store-manager'] },
      },
    } as unknown as Parameters<typeof store.setUser>[0])

    expect(store.isAdmin).toBe(true)
  })

  it('does not grant admin access to the default role when another role is configured', () => {
    const store = useAuthStore()
    store.setUser({
      expired: false,
      profile: {
        sub: 'ordinary-user',
        realm_access: { roles: ['admin'] },
      },
    } as unknown as Parameters<typeof store.setUser>[0])

    expect(store.isAdmin).toBe(false)
  })
})
