import { beforeEach, describe, expect, it, vi } from 'vitest'

const authStore = {
  accessToken: 'access-token',
  logout: vi.fn(),
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

describe('api.downloadApk', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
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
