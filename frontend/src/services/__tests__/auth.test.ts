import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { signinSilent } = vi.hoisted(() => ({ signinSilent: vi.fn() }))
vi.mock('oidc-client-ts', () => ({
  UserManager: class {
    signinSilent = signinSilent
    events = {
      addSilentRenewError: vi.fn(),
      addUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
    }
  },
  WebStorageStateStore: class {},
}))

import { authService } from '../auth'

describe('session renewal', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    signinSilent.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it('shares concurrent renewals and retries a temporary failure', async () => {
    const user = { access_token: 'renewed' }
    signinSilent.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(user)
    const first = authService.silentRenew()
    const second = authService.silentRenew()
    expect(second).toBe(first)
    await vi.runAllTimersAsync()
    await expect(first).resolves.toBe(user)
    expect(signinSilent).toHaveBeenCalledTimes(2)
  })

  it.each(['invalid_grant', 'login_required', 'interaction_required', 'consent_required', 'account_selection_required'])('requires sign-in for %s', async (error) => {
    signinSilent.mockRejectedValue({ error })
    await expect(authService.silentRenew()).resolves.toBeNull()
    expect(signinSilent).toHaveBeenCalledOnce()
  })

  it.each([new TypeError('Failed to fetch'), { error: 'server_error' }, { error: 'temporarily_unavailable' }])('preserves temporary failures after bounded retries: %s', async (error) => {
    signinSilent.mockRejectedValue(error)
    const result = expect(authService.silentRenew()).rejects.toBe(error)
    await vi.runAllTimersAsync()
    await result
    expect(signinSilent).toHaveBeenCalledTimes(3)
    signinSilent.mockResolvedValue({ access_token: 'recovered' })
    await expect(authService.silentRenew()).resolves.toMatchObject({ access_token: 'recovered' })
  })
})
