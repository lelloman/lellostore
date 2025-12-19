# Epic 5: Frontend Foundation - Implementation Plan

**Goal**: Set up Vue 3 project with Vuetify and OIDC authentication.

**Dependencies**: Epic 4 (Backend API Complete) ✅

**Status**: In Progress

---

## Task Overview

| # | Task | Status |
|---|------|--------|
| 1 | Project Initialization | done |
| 2 | Vuetify 3 Setup | done |
| 3 | Pinia State Management | done |
| 4 | OIDC Authentication Service | done |
| 5 | Auth Store Implementation | done |
| 6 | Router with Auth Guards | done |
| 7 | API Client Service | done |
| 8 | Base App Layout | done |
| 9 | Login Page | done |
| 10 | OIDC Callback Handler | done |
| 11 | Environment Configuration | un-done |
| 12 | Development Proxy Setup | un-done |

---

## Task 1: Project Initialization

**Status**: done

### Description

Initialize a new Vue 3 project using Vite with TypeScript support. This establishes the foundation for the entire frontend application.

The project should be created in a `frontend/` directory at the repository root, following the structure outlined in IMPLEMENTATION.md. We use Vite as it provides fast HMR (Hot Module Replacement) during development and optimized builds for production.

### Expected Directory Structure

```
frontend/
├── src/
│   ├── main.ts           # Application entry point
│   ├── App.vue           # Root component
│   ├── vite-env.d.ts     # Vite type declarations
│   ├── router/           # Vue Router configuration
│   ├── stores/           # Pinia stores
│   ├── views/            # Page components
│   ├── components/       # Reusable components
│   └── services/         # API and auth services
├── public/               # Static assets
├── index.html            # HTML entry point
├── package.json
├── tsconfig.json
├── vite.config.ts
└── .gitignore
```

### Commands

```bash
npm create vite@latest frontend -- --template vue-ts
cd frontend
npm install
```

### Acceptance Criteria

- [ ] Vue 3 + TypeScript project created with Vite
- [ ] `npm run dev` starts development server
- [ ] `npm run build` produces production build in `dist/`
- [ ] TypeScript compilation succeeds with no errors

---

## Task 2: Vuetify 3 Setup

**Status**: un-done

### Description

Install and configure Vuetify 3 as the UI component library. Vuetify provides Material Design components that match our design requirements (Material Design on web, as specified in SPEC.md).

Vuetify 3 is designed for Vue 3 and uses the Composition API. We'll configure it with:
- Material Design Icons (mdi)
- Light/dark theme support (as per non-functional requirements)
- Default configuration that can be customized later

### Installation

```bash
npm install vuetify @mdi/font
npm install -D vite-plugin-vuetify sass
```

### Configuration Example

Create `src/plugins/vuetify.ts`:

```typescript
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

export default createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          primary: '#1976D2',
          secondary: '#424242',
        },
      },
      dark: {
        colors: {
          primary: '#2196F3',
          secondary: '#757575',
        },
      },
    },
  },
})
```

Update `vite.config.ts`:

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

export default defineConfig({
  plugins: [
    vue(),
    vuetify({ autoImport: true }),
  ],
})
```

### Acceptance Criteria

- [ ] Vuetify installed and configured
- [ ] Material Design Icons available
- [ ] Light and dark themes configured
- [ ] Vuetify components render correctly (test with a simple button)
- [ ] Sass compilation works without errors

---

## Task 3: Pinia State Management

**Status**: un-done

### Description

Install and configure Pinia for state management. Pinia is the official Vue 3 state management solution, replacing Vuex. It provides:
- TypeScript support out of the box
- Composition API style stores
- Devtools integration
- Simpler API compared to Vuex

We'll set up the Pinia instance and create placeholder stores for `auth` and `apps` that will be implemented in subsequent tasks.

### Installation

```bash
npm install pinia
```

### Configuration

Update `src/main.ts`:

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import vuetify from './plugins/vuetify'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(vuetify)
app.use(router)

app.mount('#app')
```

Create store structure:

```
src/stores/
├── index.ts      # Re-exports all stores
├── auth.ts       # Authentication state (Task 5)
└── apps.ts       # App catalog state (Epic 6)
```

### Acceptance Criteria

- [ ] Pinia installed and registered with Vue app
- [ ] Store directory structure created
- [ ] Devtools show Pinia state (when Vue devtools installed)

---

## Task 4: OIDC Authentication Service

**Status**: un-done

### Description

Implement the OIDC authentication service using `oidc-client-ts`. This service handles the low-level OIDC protocol operations:
- OIDC discovery (fetching `.well-known/openid-configuration`)
- Authorization Code flow with PKCE
- Token storage and refresh
- Logout

The OIDC configuration (issuer URL, client ID) will be baked in at build time via environment variables, as specified in SPEC.md.

### Installation

```bash
npm install oidc-client-ts
```

### Implementation

Create `src/services/auth.ts`:

```typescript
import { UserManager, User, WebStorageStateStore } from 'oidc-client-ts'

const settings = {
  authority: import.meta.env.VITE_OIDC_ISSUER_URL,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID,
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

    // Handle silent renew errors
    this.userManager.events.addSilentRenewError((error) => {
      console.error('Silent renew error:', error)
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
}

export const authService = new AuthService()
```

### Environment Variables

Create `.env.example`:

```
VITE_OIDC_ISSUER_URL=https://your-oidc-provider.com
VITE_OIDC_CLIENT_ID=lellostore-frontend
VITE_API_BASE_URL=http://localhost:8080
```

### Acceptance Criteria

- [ ] `oidc-client-ts` installed
- [ ] AuthService class implemented with all methods
- [ ] Environment variables documented in `.env.example`
- [ ] TypeScript types for User and settings work correctly
- [ ] Silent refresh event handler configured

---

## Task 5: Auth Store Implementation

**Status**: un-done

### Description

Create the Pinia store that manages authentication state throughout the application. This store:
- Wraps the AuthService from Task 4
- Provides reactive state for user info and loading status
- Exposes actions for login, logout, and initialization
- Handles errors gracefully

The store will be used by components to check auth status and by the router guard to protect routes.

### Implementation

Create `src/stores/auth.ts`:

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authService } from '@/services/auth'
import type { User } from 'oidc-client-ts'

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)

  // Getters
  const isAuthenticated = computed(() => !!user.value && !user.value.expired)
  const accessToken = computed(() => user.value?.access_token ?? null)
  const userProfile = computed(() => user.value?.profile ?? null)
  const userRoles = computed(() => {
    // Extract roles from token claims (configurable path)
    const profile = user.value?.profile as Record<string, unknown> | undefined
    const realmAccess = profile?.realm_access as { roles?: string[] } | undefined
    return realmAccess?.roles ?? []
  })
  const isAdmin = computed(() => userRoles.value.includes('admin'))

  // Actions
  async function initialize() {
    isLoading.value = true
    error.value = null
    try {
      user.value = await authService.getUser()
    } catch (e) {
      error.value = 'Failed to initialize auth'
      console.error(e)
    } finally {
      isLoading.value = false
    }
  }

  async function login() {
    error.value = null
    try {
      await authService.login()
    } catch (e) {
      error.value = 'Login failed'
      console.error(e)
    }
  }

  async function handleCallback() {
    isLoading.value = true
    error.value = null
    try {
      user.value = await authService.handleCallback()
    } catch (e) {
      error.value = 'Authentication callback failed'
      console.error(e)
    } finally {
      isLoading.value = false
    }
  }

  async function logout() {
    error.value = null
    try {
      await authService.logout()
      user.value = null
    } catch (e) {
      error.value = 'Logout failed'
      console.error(e)
    }
  }

  return {
    // State
    user,
    isLoading,
    error,
    // Getters
    isAuthenticated,
    accessToken,
    userProfile,
    userRoles,
    isAdmin,
    // Actions
    initialize,
    login,
    handleCallback,
    logout,
  }
})
```

### Key Features

- **Reactive state**: Components automatically update when auth state changes
- **Role extraction**: Extracts roles from OIDC token claims (supports Keycloak-style `realm_access.roles`)
- **Admin check**: Computed property for easy admin role verification
- **Error handling**: Captures and exposes errors for UI display

### Acceptance Criteria

- [ ] Auth store created with all state, getters, and actions
- [ ] Store correctly wraps AuthService
- [ ] Role extraction works for the configured claim path
- [ ] `isAdmin` computed property correctly identifies admin users
- [ ] Error state is captured and exposed

---

## Task 6: Router with Auth Guards

**Status**: un-done

### Description

Configure Vue Router with authentication guards. The router:
- Defines routes for the application (Dashboard, Login, Callback)
- Implements a navigation guard that redirects unauthenticated users to login
- Handles the OIDC callback route
- Waits for auth initialization before allowing navigation

### Installation

```bash
npm install vue-router@4
```

### Implementation

Create `src/router/index.ts`:

```typescript
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { guest: true },
  },
  {
    path: '/callback',
    name: 'callback',
    component: () => import('@/views/CallbackView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// Navigation guard
router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()

  // Wait for auth to initialize on first load
  if (authStore.isLoading) {
    await authStore.initialize()
  }

  // Check if route requires authentication
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    // Save intended destination and redirect to login
    sessionStorage.setItem('redirectPath', to.fullPath)
    next({ name: 'login' })
    return
  }

  // Redirect authenticated users away from guest-only pages (login)
  if (to.meta.guest && authStore.isAuthenticated) {
    next({ name: 'dashboard' })
    return
  }

  next()
})

export default router
```

### Route Structure

| Path | Component | Auth Required | Description |
|------|-----------|---------------|-------------|
| `/` | DashboardView | Yes | Main admin dashboard (Epic 6) |
| `/login` | LoginView | No (guest only) | Login page with "Sign in" button |
| `/callback` | CallbackView | No | OIDC redirect callback handler |

### Acceptance Criteria

- [ ] Vue Router installed and configured
- [ ] Routes defined for dashboard, login, and callback
- [ ] Navigation guard blocks unauthenticated access
- [ ] Authenticated users redirected away from login page
- [ ] Redirect path saved for post-login navigation
- [ ] Lazy loading configured for route components

---

## Task 7: API Client Service

**Status**: un-done

### Description

Create an API client that handles all communication with the backend. The client:
- Automatically attaches the Bearer token to requests
- Handles 401 responses by redirecting to login
- Provides typed methods for all API endpoints
- Uses the native `fetch` API (or axios if preferred)

For this epic, we'll implement the client infrastructure and the app-related endpoints needed for Epic 6.

### Implementation

Create `src/services/api.ts`:

```typescript
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const authStore = useAuthStore()

  const headers: HeadersInit = {
    ...options.headers,
  }

  // Add auth token if available
  if (authStore.accessToken) {
    headers['Authorization'] = `Bearer ${authStore.accessToken}`
  }

  // Add content-type for JSON bodies
  if (options.body && typeof options.body === 'string') {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  })

  // Handle 401 - redirect to login
  if (response.status === 401) {
    await authStore.logout()
    router.push({ name: 'login' })
    throw new ApiError(401, 'unauthorized', 'Session expired')
  }

  // Handle error responses
  if (!response.ok) {
    const error = await response.json().catch(() => ({
      error: 'unknown',
      message: 'An error occurred',
    }))
    throw new ApiError(response.status, error.error, error.message)
  }

  // Return JSON for non-empty responses
  if (response.status === 204) {
    return undefined as T
  }

  return response.json()
}

// API Types (matching backend responses)
export interface AppVersion {
  versionCode: number
  versionName: string
  size: number
  sha256?: string
  minSdk?: number
  uploadedAt?: string
  apkUrl?: string
}

export interface App {
  packageName: string
  name: string
  description?: string
  iconUrl: string
  latestVersion?: AppVersion
  versions?: AppVersion[]
}

export interface AppsResponse {
  apps: App[]
}

// API Methods
export const api = {
  // User endpoints
  async getApps(): Promise<AppsResponse> {
    return request('/api/apps')
  },

  async getApp(packageName: string): Promise<App> {
    return request(`/api/apps/${encodeURIComponent(packageName)}`)
  },

  getIconUrl(packageName: string): string {
    return `${API_BASE}/api/apps/${encodeURIComponent(packageName)}/icon`
  },

  getApkUrl(packageName: string, versionCode: number): string {
    return `${API_BASE}/api/apps/${encodeURIComponent(packageName)}/versions/${versionCode}/apk`
  },

  // Admin endpoints
  async uploadApp(file: File, name?: string, description?: string): Promise<App> {
    const formData = new FormData()
    formData.append('file', file)
    if (name) formData.append('name', name)
    if (description) formData.append('description', description)

    return request('/api/admin/apps', {
      method: 'POST',
      body: formData,
    })
  },

  async updateApp(
    packageName: string,
    data: { name?: string; description?: string }
  ): Promise<App> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
  },

  async deleteApp(packageName: string): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}`, {
      method: 'DELETE',
    })
  },

  async deleteVersion(packageName: string, versionCode: number): Promise<void> {
    return request(
      `/api/admin/apps/${encodeURIComponent(packageName)}/versions/${versionCode}`,
      { method: 'DELETE' }
    )
  },
}

export { ApiError }
```

### Key Features

- **Automatic token injection**: Every request includes the Bearer token
- **401 handling**: Expired sessions redirect to login
- **Type safety**: All responses are typed with TypeScript interfaces
- **Error handling**: Custom `ApiError` class with status, code, and message
- **FormData support**: Upload endpoint handles multipart form data

### Acceptance Criteria

- [ ] API client created with request helper function
- [ ] Bearer token automatically attached to requests
- [ ] 401 responses trigger logout and redirect
- [ ] All API endpoints from SPEC.md implemented
- [ ] TypeScript types match backend response structure
- [ ] ApiError class provides structured error information

---

## Task 8: Base App Layout

**Status**: un-done

### Description

Create the base application layout with Vuetify navigation components. This includes:
- App bar with title and user menu
- Navigation drawer (for future use in Epic 6)
- Main content area
- User info display with logout button
- Theme toggle (light/dark)

This layout wraps all authenticated pages and provides consistent navigation.

### Implementation

Create `src/layouts/DefaultLayout.vue`:

```vue
<template>
  <v-app>
    <v-app-bar color="primary" prominent>
      <v-app-bar-nav-icon @click="drawer = !drawer" />

      <v-toolbar-title>lellostore</v-toolbar-title>

      <v-spacer />

      <!-- Theme toggle -->
      <v-btn icon @click="toggleTheme">
        <v-icon>{{ isDark ? 'mdi-weather-sunny' : 'mdi-weather-night' }}</v-icon>
      </v-btn>

      <!-- User menu -->
      <v-menu v-if="authStore.isAuthenticated">
        <template #activator="{ props }">
          <v-btn icon v-bind="props">
            <v-icon>mdi-account-circle</v-icon>
          </v-btn>
        </template>
        <v-list>
          <v-list-item>
            <v-list-item-title>{{ userEmail }}</v-list-item-title>
            <v-list-item-subtitle v-if="authStore.isAdmin">
              Administrator
            </v-list-item-subtitle>
          </v-list-item>
          <v-divider />
          <v-list-item @click="handleLogout">
            <template #prepend>
              <v-icon>mdi-logout</v-icon>
            </template>
            <v-list-item-title>Logout</v-list-item-title>
          </v-list-item>
        </v-list>
      </v-menu>
    </v-app-bar>

    <v-navigation-drawer v-model="drawer" temporary>
      <v-list nav>
        <v-list-item
          prepend-icon="mdi-view-dashboard"
          title="Dashboard"
          to="/"
        />
        <!-- More nav items will be added in Epic 6 -->
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <v-container fluid>
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useTheme } from 'vuetify'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const authStore = useAuthStore()
const router = useRouter()
const theme = useTheme()

const drawer = ref(false)

const isDark = computed(() => theme.global.current.value.dark)
const userEmail = computed(
  () => authStore.userProfile?.email ?? authStore.userProfile?.name ?? 'User'
)

function toggleTheme() {
  theme.global.name.value = isDark.value ? 'light' : 'dark'
}

async function handleLogout() {
  await authStore.logout()
  router.push({ name: 'login' })
}
</script>
```

Update `src/App.vue`:

```vue
<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
</template>
```

### Acceptance Criteria

- [ ] App bar with title displays correctly
- [ ] Navigation drawer opens/closes
- [ ] User email/name shown in menu
- [ ] Admin badge shown for admin users
- [ ] Logout button works
- [ ] Theme toggle switches between light/dark
- [ ] Layout properly wraps content with slot

---

## Task 9: Login Page

**Status**: un-done

### Description

Create a simple login page that displays when users are not authenticated. The page shows:
- Application branding/logo
- "Sign in" button that initiates the OIDC flow
- Loading state while checking auth status
- Error message if login fails

### Implementation

Create `src/views/LoginView.vue`:

```vue
<template>
  <v-app>
    <v-main>
      <v-container class="fill-height" fluid>
        <v-row align="center" justify="center">
          <v-col cols="12" sm="8" md="4">
            <v-card class="elevation-12">
              <v-toolbar color="primary" dark flat>
                <v-toolbar-title>lellostore</v-toolbar-title>
              </v-toolbar>

              <v-card-text class="text-center py-8">
                <v-icon size="64" color="primary" class="mb-4">
                  mdi-package-variant-closed
                </v-icon>
                <p class="text-h6 mb-4">Private App Distribution</p>
                <p class="text-body-2 text-medium-emphasis mb-6">
                  Sign in to access the admin dashboard
                </p>

                <v-alert
                  v-if="authStore.error"
                  type="error"
                  variant="tonal"
                  class="mb-4"
                >
                  {{ authStore.error }}
                </v-alert>

                <v-btn
                  color="primary"
                  size="large"
                  block
                  :loading="isLoggingIn"
                  @click="handleLogin"
                >
                  <v-icon start>mdi-login</v-icon>
                  Sign in with SSO
                </v-btn>
              </v-card-text>
            </v-card>
          </v-col>
        </v-row>
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const isLoggingIn = ref(false)

async function handleLogin() {
  isLoggingIn.value = true
  await authStore.login()
  // Note: This redirects to OIDC provider, so loading state
  // stays until redirect happens
}
</script>
```

### Acceptance Criteria

- [ ] Login page displays centered card with branding
- [ ] "Sign in with SSO" button visible
- [ ] Clicking button initiates OIDC redirect
- [ ] Loading state shown while redirecting
- [ ] Error message displayed if auth fails
- [ ] Page styling matches Vuetify Material Design

---

## Task 10: OIDC Callback Handler

**Status**: un-done

### Description

Create a callback page that handles the OIDC redirect after successful authentication. This page:
- Processes the authorization code from the URL
- Exchanges it for tokens via the auth service
- Redirects to the originally requested page (or dashboard)
- Shows loading state during processing
- Handles and displays errors

### Implementation

Create `src/views/CallbackView.vue`:

```vue
<template>
  <v-app>
    <v-main>
      <v-container class="fill-height" fluid>
        <v-row align="center" justify="center">
          <v-col cols="12" class="text-center">
            <template v-if="error">
              <v-icon size="64" color="error" class="mb-4">
                mdi-alert-circle
              </v-icon>
              <p class="text-h6 mb-2">Authentication Failed</p>
              <p class="text-body-2 text-medium-emphasis mb-6">
                {{ error }}
              </p>
              <v-btn color="primary" @click="goToLogin">
                Try Again
              </v-btn>
            </template>

            <template v-else>
              <v-progress-circular
                indeterminate
                size="64"
                color="primary"
                class="mb-4"
              />
              <p class="text-h6">Completing sign in...</p>
            </template>
          </v-col>
        </v-row>
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    await authStore.handleCallback()

    // Redirect to saved path or dashboard
    const redirectPath = sessionStorage.getItem('redirectPath') || '/'
    sessionStorage.removeItem('redirectPath')

    router.replace(redirectPath)
  } catch (e) {
    console.error('Callback error:', e)
    error.value = e instanceof Error ? e.message : 'Authentication failed'
  }
})

function goToLogin() {
  router.push({ name: 'login' })
}
</script>
```

### Acceptance Criteria

- [ ] Callback page processes OIDC response on mount
- [ ] Loading spinner shown during processing
- [ ] Successful auth redirects to saved path or dashboard
- [ ] Errors displayed with retry option
- [ ] No flash of content before redirect

---

## Task 11: Environment Configuration

**Status**: un-done

### Description

Set up environment-based configuration for development, staging, and production. Create:
- `.env.example` with all required variables documented
- `.env.development` with local development defaults
- TypeScript type declarations for import.meta.env
- Documentation in README

### Configuration Files

Create `.env.example`:

```bash
# OIDC Configuration (required)
VITE_OIDC_ISSUER_URL=https://your-oidc-provider.com/realms/your-realm
VITE_OIDC_CLIENT_ID=lellostore-frontend

# API Configuration
# Leave empty in production (served from same origin)
# Set to backend URL in development (e.g., http://localhost:8080)
VITE_API_BASE_URL=
```

Create `.env.development`:

```bash
# Development configuration
VITE_OIDC_ISSUER_URL=https://your-dev-oidc-provider.com/realms/dev
VITE_OIDC_CLIENT_ID=lellostore-dev
VITE_API_BASE_URL=http://localhost:8080
```

Create `src/env.d.ts`:

```typescript
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_ISSUER_URL: string
  readonly VITE_OIDC_CLIENT_ID: string
  readonly VITE_API_BASE_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
```

### Acceptance Criteria

- [ ] `.env.example` documents all environment variables
- [ ] `.env.development` has working development defaults
- [ ] TypeScript recognizes env variable types
- [ ] Vite correctly loads environment variables
- [ ] `.env*` files (except .example) added to .gitignore

---

## Task 12: Development Proxy Setup

**Status**: un-done

### Description

Configure Vite's development proxy to forward API requests to the backend server. This:
- Avoids CORS issues during development
- Simulates production setup (frontend and API on same origin)
- Allows running frontend and backend on different ports

### Implementation

Update `vite.config.ts`:

```typescript
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [
      vue(),
      vuetify({ autoImport: true }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
```

### Development Workflow

1. Start backend: `cd backend && cargo run` (port 8080)
2. Start frontend: `cd frontend && npm run dev` (port 3000)
3. Access frontend at `http://localhost:3000`
4. API calls to `/api/*` are proxied to backend

### Acceptance Criteria

- [ ] Vite dev server runs on port 3000
- [ ] API requests proxied to backend on port 8080
- [ ] No CORS errors during development
- [ ] Path alias `@` resolves to `src/` directory

---

## Verification Checklist

When all tasks are complete, verify the following:

### Authentication Flow

- [ ] Clicking "Sign in" redirects to OIDC provider
- [ ] Successful login returns to app and shows dashboard
- [ ] User info displayed in app bar
- [ ] Logout button clears session and shows login page
- [ ] Refreshing page maintains authentication
- [ ] Token silent refresh works before expiry

### Routing & Guards

- [ ] Unauthenticated users redirected to login
- [ ] Authenticated users can access dashboard
- [ ] Deep links work after login (redirect to original URL)

### API Integration

- [ ] API calls include Bearer token
- [ ] 401 response triggers logout and redirect
- [ ] API errors displayed appropriately

### Developer Experience

- [ ] `npm run dev` starts with hot reload
- [ ] `npm run build` produces production bundle
- [ ] TypeScript compilation has no errors
- [ ] Vuetify components render correctly

---

## Files to Create

```
frontend/
├── .env.example
├── .env.development
├── .gitignore
├── index.html
├── package.json
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts
├── public/
│   └── favicon.ico
└── src/
    ├── main.ts
    ├── App.vue
    ├── env.d.ts
    ├── vite-env.d.ts
    ├── plugins/
    │   └── vuetify.ts
    ├── router/
    │   └── index.ts
    ├── stores/
    │   ├── index.ts
    │   └── auth.ts
    ├── services/
    │   ├── api.ts
    │   └── auth.ts
    ├── layouts/
    │   └── DefaultLayout.vue
    └── views/
        ├── DashboardView.vue
        ├── LoginView.vue
        └── CallbackView.vue
```
