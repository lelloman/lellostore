import { UserManager, User, WebStorageStateStore } from 'oidc-client-ts'
import { loadServerConfig } from './serverConfig'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

export interface CurrentIdentity {
  subject: string
  email?: string
  is_admin: boolean
}

class AuthService {
  private manager: Promise<UserManager> | null = null
  private renewal: Promise<User | null> | null = null
  private loadedListeners = new Set<(user: User) => void>()
  private unloadedListeners = new Set<() => void>()

  private getManager(): Promise<UserManager> {
    if (!this.manager) {
      this.manager = loadServerConfig().then(config => {
        const manager = new UserManager({
          authority: config.auth.issuer_url,
          client_id: config.auth.clients.web,
          redirect_uri: `${window.location.origin}/callback`,
          post_logout_redirect_uri: `${window.location.origin}/callback`,
          response_type: 'code',
          scope: config.auth.scopes.join(' '),
          automaticSilentRenew: true,
          userStore: new WebStorageStateStore({ store: window.localStorage }),
        })
        manager.events.addSilentRenewError(error => console.error('Silent renew error:', error))
        manager.events.addUserLoaded(user => this.loadedListeners.forEach(callback => callback(user)))
        manager.events.addUserUnloaded(() => this.unloadedListeners.forEach(callback => callback()))
        return manager
      }).catch(error => { this.manager = null; throw error })
    }
    return this.manager
  }

  async login(): Promise<void> {
    await (await this.getManager()).signinRedirect()
  }

  async handleCallback(): Promise<User> {
    return await (await this.getManager()).signinRedirectCallback()
  }

  async handleLogoutCallback(): Promise<void> {
    await (await this.getManager()).signoutRedirectCallback()
  }

  async logout(): Promise<void> {
    await (await this.getManager()).signoutRedirect()
  }

  async clearLocalSession(): Promise<void> {
    await (await this.getManager()).removeUser()
  }

  onUserLoaded(callback: (user: User) => void): () => void {
    this.loadedListeners.add(callback)
    return () => { this.loadedListeners.delete(callback) }
  }

  onUserUnloaded(callback: () => void): () => void {
    this.unloadedListeners.add(callback)
    return () => { this.unloadedListeners.delete(callback) }
  }

  async getUser(): Promise<User | null> {
    return await (await this.getManager()).getUser()
  }

  async getAccessToken(): Promise<string | null> {
    const user = await this.getUser()
    return user?.access_token ?? null
  }

  async isAuthenticated(): Promise<boolean> {
    const user = await this.getUser()
    return !!user && !user.expired
  }

  async getCurrentIdentity(accessToken: string): Promise<CurrentIdentity> {
    const response = await fetch(`${API_BASE}/api/me`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    if (!response.ok) {
      throw new Error(`Identity request failed with status ${response.status}`)
    }
    return await response.json() as CurrentIdentity
  }

  silentRenew(): Promise<User | null> {
    if (!this.renewal) {
      this.renewal = this.renewWithRetry().finally(() => {
        this.renewal = null
      })
    }
    return this.renewal
  }

  private async renewWithRetry(): Promise<User | null> {
    const manager = await this.getManager()
    for (let attempt = 0; ; attempt++) {
      try {
        return await manager.signinSilent()
      } catch (error) {
        const code = error && typeof error === 'object' && 'error' in error
          ? error.error : undefined
        // Only an explicit provider rejection makes the saved session unusable.
        if (['invalid_grant', 'login_required', 'interaction_required', 'consent_required', 'account_selection_required'].includes(String(code))) {
          return null
        }
        if (attempt >= 2) throw error
        await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)))
      }
    }
  }
}

export const authService = new AuthService()
export type { User }
