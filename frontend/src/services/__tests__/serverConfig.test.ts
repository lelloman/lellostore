import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseServerConfig } from '../serverConfig'

const valid = () => ({
  schema_version: 1, name: 'Independent Store',
  auth: { method: 'oidc', issuer_url: 'https://identity.example/realm',
    clients: { android: 'android', web: 'web', publisher: 'publisher' }, scopes: ['openid', 'email'] },
  capabilities: { push: false, paravoid: true },
})

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

describe('server discovery', () => {
  it('accepts independent provider configuration and ignores additive fields', () => {
    expect(parseServerConfig({ ...valid(), future: true })).toEqual(valid())
  })

  it('rejects unsupported protocols and insecure provider URLs', () => {
    expect(() => parseServerConfig({ ...valid(), schema_version: 2 })).toThrow('unsupported setup version')
    expect(() => parseServerConfig({ ...valid(), auth: { ...valid().auth, method: 'password' } })).toThrow('unsupported sign-in')
    for (const issuer_url of ['http://identity.example', 'https://user:secret@identity.example', 'https://identity.example?token=secret', 'https://identity.example/#fragment']) {
      expect(() => parseServerConfig({ ...valid(), auth: { ...valid().auth, issuer_url } })).toThrow('HTTPS')
    }
  })

  it('rejects incomplete and malformed metadata', () => {
    for (const value of [null, {}, { ...valid(), name: '' }, { ...valid(), auth: { ...valid().auth, scopes: ['email'] } },
      { ...valid(), capabilities: {} }, { ...valid(), auth: { ...valid().auth, clients: {} } }]) {
      expect(() => parseServerConfig(value)).toThrow()
    }
  })

  it('retries failed discovery without credentials or cross-origin redirects', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => valid() })
    vi.stubGlobal('fetch', fetch)
    const { loadServerConfig, storeName } = await import('../serverConfig')
    await expect(loadServerConfig()).rejects.toThrow('not configured')
    await expect(loadServerConfig()).resolves.toEqual(valid())
    expect(storeName.value).toBe('Independent Store')
    expect(fetch).toHaveBeenCalledWith('/api/server-config', expect.objectContaining({ credentials: 'omit', redirect: 'error', cache: 'no-store' }))
    await loadServerConfig()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
