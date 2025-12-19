import { UserManager, User, WebStorageStateStore } from 'oidc-client-ts'

const settings = {
  authority: import.meta.env.VITE_OIDC_ISSUER_URL || 'https://example.com',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID || 'lellostore',
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
}

class AuthService {
  private userManager: UserManager

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

  async logout(): Promise<void> {
    await this.userManager.signoutRedirect()
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

  async silentRenew(): Promise<User | null> {
    try {
      return await this.userManager.signinSilent()
    } catch (error) {
      console.error('Silent renew failed:', error)
      return null
    }
  }
}

export const authService = new AuthService()
export type { User }
