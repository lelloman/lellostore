import { UserManager, User, WebStorageStateStore } from 'oidc-client-ts'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

export interface CurrentIdentity {
  subject: string
  email?: string
  is_admin: boolean
}

const settings = {
  authority: import.meta.env.VITE_OIDC_ISSUER_URL || 'https://example.com',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID || 'lellostore',
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/callback`,
  response_type: 'code',
  scope: 'openid profile email offline_access',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
}

class AuthService {
  private userManager: UserManager
  private renewal: Promise<User | null> | null = null

  constructor() {
    this.userManager = new UserManager(settings)

    this.userManager.events.addSilentRenewError((error) => {
      console.error('Silent renew error:', error)
    })

    this.userManager.events.addUserLoaded((user) => {
      console.debug('User loaded:', user.profile.sub)
    })

    this.userManager.events.addUserUnloaded(() => {
      console.debug('User unloaded')
    })
  }

  async login(): Promise<void> {
    await this.userManager.signinRedirect()
  }

  async handleCallback(): Promise<User> {
    return await this.userManager.signinRedirectCallback()
  }

  async handleLogoutCallback(): Promise<void> {
    await this.userManager.signoutRedirectCallback()
  }

  async logout(): Promise<void> {
    await this.userManager.signoutRedirect()
  }

  async clearLocalSession(): Promise<void> {
    await this.userManager.removeUser()
  }

  onUserLoaded(callback: (user: User) => void): () => void {
    return this.userManager.events.addUserLoaded(callback)
  }

  onUserUnloaded(callback: () => void): () => void {
    return this.userManager.events.addUserUnloaded(callback)
  }

  async getUser(): Promise<User | null> {
    return await this.userManager.getUser()
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
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.userManager.signinSilent()
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
