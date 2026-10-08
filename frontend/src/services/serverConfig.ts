import { computed, shallowRef } from 'vue'

export interface ServerConfig {
  schema_version: 1
  name: string
  auth: {
    method: 'oidc'
    issuer_url: string
    clients: { android: string; web: string; publisher: string }
    scopes: string[]
  }
  capabilities: { push: boolean; paravoid: boolean }
}

const currentConfig = shallowRef<ServerConfig | null>(null)
let pending: Promise<ServerConfig> | null = null
export const storeName = computed(() => currentConfig.value?.name ?? 'App Store')

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid server configuration.')
  return value as Record<string, unknown>
}

function text(value: unknown, max = 256): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max || Array.from(value).some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) {
    throw new Error('Invalid server configuration.')
  }
  return value
}

export function parseServerConfig(value: unknown): ServerConfig {
  const root = object(value)
  if (root.schema_version !== 1) throw new Error('This server uses an unsupported setup version. Update the app or contact its operator.')
  const auth = object(root.auth)
  if (auth.method !== 'oidc') throw new Error('This server uses an unsupported sign-in method.')
  const issuer = text(auth.issuer_url, 2048)
  const url = new URL(issuer)
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
    throw new Error('The sign-in provider must use a secure HTTPS address.')
  }
  const clients = object(auth.clients)
  const capabilities = object(root.capabilities)
  const scopes = auth.scopes
  if (!Array.isArray(scopes) || scopes.length > 32 || !scopes.includes('openid') ||
    scopes.some(scope => typeof scope !== 'string' || !/^[\x21\x23-\x5b\x5d-\x7e]{1,128}$/u.test(scope)) ||
    typeof capabilities.push !== 'boolean' || typeof capabilities.paravoid !== 'boolean') {
    throw new Error('Invalid server configuration.')
  }
  return {
    schema_version: 1,
    name: text(root.name, 120),
    auth: { method: 'oidc', issuer_url: issuer, scopes,
      clients: { android: text(clients.android), web: text(clients.web), publisher: text(clients.publisher) } },
    capabilities: { push: capabilities.push, paravoid: capabilities.paravoid },
  }
}

export function loadServerConfig(): Promise<ServerConfig> {
  if (currentConfig.value) return Promise.resolve(currentConfig.value)
  if (!pending) {
    pending = fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/server-config`, {
      credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10000),
    }).then(async response => {
      if (response.status === 503) throw new Error('This store is not configured yet. Ask its operator to finish setup.')
      if (!response.ok) throw new Error('Unable to read this store’s configuration. Check the server and retry.')
      const config = parseServerConfig(await response.json())
      currentConfig.value = config
      return config
    }).finally(() => { pending = null })
  }
  return pending
}
