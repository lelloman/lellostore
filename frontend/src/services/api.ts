import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

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

async function request<T>(
  path: string,
  options: RequestInit = {}
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
    let error = { error: 'unknown', message: 'An error occurred' }
    try {
      error = await response.json()
    } catch {
      // Response wasn't JSON
    }
    throw new ApiError(response.status, error.error, error.message)
  }

  // Return undefined for 204 No Content
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

export interface UploadResponse {
  packageName: string
  name: string
  description?: string
  iconUrl: string
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
