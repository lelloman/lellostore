import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'
import { authService } from '@/services/auth'

let userLoadedListener: ((user: Awaited<ReturnType<typeof authService.getUser>>) => void) | undefined
let userUnloadedListener: (() => void) | undefined

vi.mock('@/services/auth', () => ({
  authService: {
    getUser: vi.fn(),
    login: vi.fn(),
    handleCallback: vi.fn(),
    handleLogoutCallback: vi.fn(),
    logout: vi.fn(),
    silentRenew: vi.fn(),
    clearLocalSession: vi.fn(),
    onUserLoaded: vi.fn((callback) => {
      userLoadedListener = callback
      return vi.fn()
    }),
    onUserUnloaded: vi.fn((callback) => {
      userUnloadedListener = callback
      return vi.fn()
    }),
    getCurrentIdentity: vi.fn(),
  },
}))

describe('Auth Store authorization', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    vi.stubEnv('VITE_OIDC_ADMIN_ROLE', 'store-manager')
    userLoadedListener = undefined
    userUnloadedListener = undefined
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

  it('uses backend admin status when the OIDC profile has no roles', async () => {
    vi.mocked(authService.getUser).mockResolvedValue({
      expired: false,
      access_token: 'access-token',
      profile: { sub: 'global-admin' },
    } as Awaited<ReturnType<typeof authService.getUser>>)
    vi.mocked(authService.getCurrentIdentity).mockResolvedValue({
      subject: 'global-admin',
      is_admin: true,
    })
    const store = useAuthStore()

    await store.initialize()

    expect(store.isAdmin).toBe(true)
  })

  it('renews an expired stored user during initialization', async () => {
    vi.mocked(authService.getUser).mockResolvedValue({
      expired: true,
      access_token: 'expired-token',
      profile: { sub: 'returning-user' },
    } as Awaited<ReturnType<typeof authService.getUser>>)
    vi.mocked(authService.silentRenew).mockResolvedValue({
      expired: false,
      access_token: 'renewed-token',
      profile: { sub: 'returning-user' },
    } as Awaited<ReturnType<typeof authService.silentRenew>>)
    vi.mocked(authService.getCurrentIdentity).mockResolvedValue({
      subject: 'returning-user',
      is_admin: false,
    })
    const store = useAuthStore()

    await store.initialize()

    expect(authService.silentRenew).toHaveBeenCalledOnce()
    expect(store.accessToken).toBe('renewed-token')
    expect(store.isAuthenticated).toBe(true)
  })

  it('clears an expired local session when renewal is unavailable', async () => {
    vi.mocked(authService.getUser).mockResolvedValue({
      expired: true,
      access_token: 'expired-token',
      profile: { sub: 'returning-user' },
    } as Awaited<ReturnType<typeof authService.getUser>>)
    vi.mocked(authService.silentRenew).mockResolvedValue(null)
    const store = useAuthStore()

    await store.initialize()

    expect(authService.clearLocalSession).toHaveBeenCalledOnce()
    expect(store.isAuthenticated).toBe(false)
  })

  it('tracks users loaded by automatic silent renewal', () => {
    const store = useAuthStore()
    const renewedUser = {
      expired: false,
      access_token: 'automatic-renewal-token',
      profile: { sub: 'returning-user' },
    } as Awaited<ReturnType<typeof authService.getUser>>

    userLoadedListener?.(renewedUser)

    expect(store.accessToken).toBe('automatic-renewal-token')
    expect(store.isAuthenticated).toBe(true)
  })

  it('clears Pinia state when the OIDC user is unloaded', () => {
    const store = useAuthStore()
    store.setUser({
      expired: false,
      access_token: 'access-token',
      profile: { sub: 'returning-user' },
    } as Parameters<typeof store.setUser>[0])

    userUnloadedListener?.()

    expect(store.accessToken).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})
