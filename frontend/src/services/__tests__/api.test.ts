import { beforeEach, describe, expect, it, vi } from 'vitest'

const authStore = {
  accessToken: 'access-token',
  logout: vi.fn(),
  clearSession: vi.fn(),
  setUser: vi.fn(),
}

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authStore,
}))

vi.mock('@/services/auth', () => ({
  authService: {
    silentRenew: vi.fn(),
  },
}))

vi.mock('@/router', () => ({
  default: { push: vi.fn() },
}))

import { api } from '@/services/api'
import { authService } from '@/services/auth'
import router from '@/router'

describe('api.downloadApk', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    authStore.accessToken = 'access-token'
  })

  it('downloads the APK with the current bearer token', async () => {
    const expected = new Blob(['apk-bytes'], {
      type: 'application/vnd.android.package-archive',
    })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      status: 200,
      ok: true,
      blob: vi.fn().mockResolvedValue(expected),
    } as unknown as Response)

    const result = await api.downloadApk('com.example.app', 42)

    expect(result).toBe(expected)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/apps/com.example.app/versions/42/apk',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
        }),
      })
    )
  })
})

describe('api authentication recovery', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    authStore.accessToken = 'expired-token'
  })

  it('clears only the local session when a 401 cannot be renewed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      status: 401,
      ok: false,
    } as Response)
    vi.mocked(authService.silentRenew).mockResolvedValue(null)

    await expect(api.getApps()).rejects.toMatchObject({ status: 401 })

    expect(authStore.clearSession).toHaveBeenCalledOnce()
    expect(authStore.logout).not.toHaveBeenCalled()
  })

  it('preserves the session and stays on the page when renewal is temporarily unavailable', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 401, ok: false } as Response)
    vi.mocked(authService.silentRenew).mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(api.getApps()).rejects.toMatchObject({ code: 'auth_unavailable' })
    expect(authStore.clearSession).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()

    vi.mocked(authService.silentRenew).mockResolvedValue({ access_token: 'renewed' } as Awaited<ReturnType<typeof authService.silentRenew>>)
    vi.mocked(fetch).mockResolvedValueOnce({ status: 401, ok: false } as Response)
      .mockResolvedValueOnce({ status: 200, ok: true, json: async () => ({ apps: [] }) } as Response)
    await expect(api.getApps()).resolves.toEqual({ apps: [] })
  })

  it('does not erase a renewed session when the API still rejects it', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 401, ok: false } as Response)
    vi.mocked(authService.silentRenew).mockResolvedValue({ access_token: 'renewed' } as Awaited<ReturnType<typeof authService.silentRenew>>)
    await expect(api.getApps()).rejects.toMatchObject({ status: 401 })
    expect(authStore.clearSession).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })
})

describe('api access administration', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    authStore.accessToken = 'access-token'
  })

  it('encodes direct-grant targets and sends the selected access level', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      status: 204,
      ok: true,
    } as Response)

    await api.setDirectGrant('oidc/user 1', 'com.example.app', 'beta')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/users/oidc%2Fuser%201/apps/com.example.app',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ access_level: 'beta' }),
      })
    )
  })

  it('uses the dedicated complete catalogue for administration', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      status: 200,
      ok: true,
      json: vi.fn().mockResolvedValue({ apps: [] }),
    } as unknown as Response)

    await api.getAdminApps()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/apps',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer access-token' }) })
    )
  })
})
