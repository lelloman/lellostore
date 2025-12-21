import { useAuthStore } from '@/stores/auth'
import { authService } from '@/services/auth'
import router from '@/router'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

// Track if a token refresh is in progress to avoid multiple concurrent refreshes
let refreshPromise: Promise<string | null> | null = null

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function refreshAccessToken(): Promise<string | null> {
  // If a refresh is already in progress, wait for it
  if (refreshPromise) {
    return refreshPromise
  }

  refreshPromise = (async () => {
    try {
      const user = await authService.silentRenew()
      if (user?.access_token) {
        const authStore = useAuthStore()
        authStore.setUser(user)
        return user.access_token
      }
      return null
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  isRetry = false
): Promise<T> {
  const authStore = useAuthStore()

  const headers: HeadersInit = {
    ...(options.headers as Record<string, string>),
  }

  // Add auth token if available
  if (authStore.accessToken) {
    headers['Authorization'] = `Bearer ${authStore.accessToken}`
  }

  // Add content-type for JSON bodies (but not FormData)
  if (options.body && typeof options.body === 'string') {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers,
    })
  } catch (e) {
    // Network error (offline, DNS failure, CORS, etc.)
    throw new ApiError(0, 'network_error', 'Unable to connect to server. Please check your connection.')
  }

  // Handle 401 - attempt token refresh and retry once
  if (response.status === 401) {
    if (!isRetry && authStore.accessToken) {
      // Try to refresh the token
      const newToken = await refreshAccessToken()
      if (newToken) {
        // Retry the request with the new token
        return request<T>(path, options, true)
      }
    }
    // Refresh failed or this was already a retry - logout
    await authStore.logout()
    router.push({ name: 'login' })
    throw new ApiError(401, 'unauthorized', 'Session expired')
  }

  // Handle error responses
  if (!response.ok) {
    let error = { error: 'unknown', message: 'An error occurred' }
    try {
      error = await response.json()
    } catch {
      // Response wasn't JSON, map status codes to friendly messages
      switch (response.status) {
        case 403:
          error.error = 'forbidden'
          error.message = "You don't have permission to perform this action"
          break
        case 404:
          error.error = 'not_found'
          error.message = 'The requested resource was not found'
          break
        case 409:
          error.error = 'conflict'
          error.message = 'Version already exists. Delete it first to re-upload.'
          break
        case 413:
          error.error = 'payload_too_large'
          error.message = 'File is too large. Maximum size is 500MB.'
          break
        case 415:
          error.error = 'unsupported_media_type'
          error.message = 'Invalid file type. Only APK and AAB files are supported.'
          break
        case 500:
        case 502:
        case 503:
          error.error = 'server_error'
          error.message = 'An unexpected server error occurred. Please try again later.'
          break
      }
    }
    throw new ApiError(response.status, error.error, error.message)
  }

  // Return undefined for 204 No Content
  if (response.status === 204) {
    return undefined as T
  }

  return response.json()
}

// API Types (matching backend responses - snake_case)
export interface AppVersion {
  version_code: number
  version_name: string
  size: number
  sha256: string
  min_sdk: number
  uploaded_at: string
  apk_url: string
}

// Version info in list endpoint (subset of full version)
export interface LatestVersionInfo {
  version_code: number
  version_name: string
  size: number
  min_sdk: number
  uploaded_at: string
}

// App in list response
export interface AppListItem {
  package_name: string
  name: string
  description?: string
  icon_url: string
  latest_version?: LatestVersionInfo
}

// App in detail response
export interface App {
  package_name: string
  name: string
  description?: string
  icon_url: string
  versions: AppVersion[]
}

export interface AppsResponse {
  apps: AppListItem[]
}

export interface UploadResponse {
  package_name: string
  name: string
  description?: string
  icon_url: string
  version: AppVersion
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
  async uploadApp(
    file: File,
    name?: string,
    description?: string
  ): Promise<UploadResponse> {
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
