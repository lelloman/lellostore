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
    } catch {
      throw new ApiError(0, 'auth_unavailable', 'Unable to renew your session. Please retry when your connection is restored.')
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  isRetry = false,
  parseResponse: (response: Response) => Promise<T> = (response) => response.json()
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
  } catch {
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
        return request<T>(path, options, true, parseResponse)
      }
    }
    if (isRetry) {
      throw new ApiError(401, 'unauthorized', 'The server rejected the renewed session. Please retry.')
    }
    // The local session is no longer usable. Do not sign the user out of the
    // identity provider just because this application could not refresh it.
    await authStore.clearSession()
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
          error.message = 'The release changed or its identity is already in use. Refresh the review.'
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

  return parseResponse(response)
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
  is_beta?: boolean
  publication_state?: 'draft' | 'published' | 'withdrawn'
  distribution_mode?: 'normal' | 'paravoid'
  release_notes?: string
  published_at?: string | null
}

export type AccessLevel = 'stable' | 'beta'

// Version info in list endpoint (subset of full version)
export interface LatestVersionInfo {
  publication_state?: 'draft' | 'published' | 'withdrawn'
  version_code: number
  version_name: string
  size: number
  min_sdk: number
  uploaded_at: string
  is_beta?: boolean
}

// App in list response
export interface AppListItem {
  package_name: string
  name: string
  description?: string
  icon_url: string
  total_size: number
  latest_version?: LatestVersionInfo
  access_level?: AccessLevel
  distribution_mode?: 'normal' | 'paravoid'
  publication_revision?: number
}

// App in detail response
export interface App {
  package_name: string
  name: string
  description?: string
  icon_url: string
  versions: AppVersion[]
  access_level?: AccessLevel
  distribution_mode?: 'normal' | 'paravoid'
  publication_revision?: number
}

export interface KnownUser {
  subject: string
  email?: string
  first_seen_at: string
  last_seen_at: string
}

export interface AppGrant {
  package_name: string
  access_level: AccessLevel
}

export interface AppGroup {
  id: number
  name: string
  system_kind: 'all' | null
  created_at: string
  updated_at: string
  grants: AppGrant[]
  user_subjects: string[]
}

export interface UserAccess {
  user: KnownUser
  direct_grants: AppGrant[]
  groups: Array<Omit<AppGroup, 'grants' | 'user_subjects'>>
  effective_access: AppGrant[]
}

export interface AppsResponse {
  apps: AppListItem[]
}

export interface ShellContract {
  package_name: string; contract_id: string; installer_version: number
  channel: string; authentication: string; bootstrap: string; base_url: string
  verification_state: string; validation_report: string
}
export interface VpkRelease {
  id: string; package_name: string; contract_id: string; release_id: string; payload_version: number
  archive_size: number; archive_sha256: string; manifest_sha256: string; manifest_json: string
  min_sdk: number; max_sdk: number; abis_json: string; signing_key_id: string
  validation_state: string; validation_report: string; publication_state: string; release_notes: string
}
export interface ParavoidGrant {
  id: string; key_id: string; contract_id: string; installer_version: number; user_subject: string
  issued_at: number; expires_at: number; revoked_at: number | null; last_used_at: number | null; request_count: number
}
export interface DistributionReview {
  id: string; from_mode: string; to_mode: string; from_version: number; target_version: number
  target_sha256: string; signer_sha256: string; review_revision: number; migration_evidence: string
  actor_subject: string; created_at: string
}
export interface AppDistribution {
  distribution_mode: string; publication_revision: number; contracts: ShellContract[]; releases: VpkRelease[]
  streams: { contract_id: string; revision: number; status: string }[]
  grants: ParavoidGrant[]
  events: { id: number; action: string; actor_subject: string; created_at: string; revision: number }[]
}

export interface ParavoidConfiguration {
  configured: boolean
  distribution_enabled: boolean
  signing: null | {
    base_url: string
    head_keys: Record<string, string>
    grant_keys: Record<string, string>
    head_fingerprints: Record<string, string>
    grant_fingerprints: Record<string, string>
    active_head_key: string
    active_grant_key: string
  }
}

export interface UploadJob {
  id: string
  file_name: string
  actor_subject: string
  status: 'queued' | 'validating' | 'ready' | 'failed'
  result_json: string | null
  error: string | null
  created_at: string
}

export interface UploadResponse {
  package_name: string
  name: string
  description?: string
  icon_url: string
  version: AppVersion
}

export interface PublicationEvent {
  id: number
  version_code: number
  revision: number
  actor_subject: string
  action: 'publish' | 'withdraw'
  created_at: string
}

export interface PublicationResult {
  package_name: string
  version_code: number
  publication_revision: number
  publication_state: 'published' | 'withdrawn'
}

export interface ApkAcquisition {
  id: string
  package_name: string
  version_code: number
  size: number
  sha256: string
  expires_at: number
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

  async downloadApk(packageName: string, versionCode: number, purpose: 'install' | 'repair' = 'install'): Promise<Blob> {
    const acquisition = await api.acquireApk(packageName, versionCode, purpose)
    if (acquisition.package_name !== packageName || acquisition.version_code !== versionCode ||
      !/^[a-zA-Z0-9_-]{1,128}$/.test(acquisition.id) ||
      !/^[a-fA-F0-9]{64}$/.test(acquisition.sha256) || !Number.isSafeInteger(acquisition.size) || acquisition.size <= 0) {
      throw new Error('The server returned invalid acquisition metadata')
    }
    const blob = await request<Blob>(
      `/api/acquisitions/${encodeURIComponent(acquisition.id)}/apk`,
      { redirect: 'error' },
      false,
      (response) => response.blob()
    )
    if (blob.size !== acquisition.size) throw new Error('Downloaded APK size does not match its acquisition')
    const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
    const sha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
    if (sha256 !== acquisition.sha256.toLowerCase()) throw new Error('Downloaded APK failed integrity verification')
    return blob
  },

  async acquireApk(packageName: string, versionCode: number, purpose: 'install' | 'repair' = 'install'): Promise<ApkAcquisition> {
    return request(`/api/apps/${encodeURIComponent(packageName)}/acquisitions`, {
      method: 'POST', redirect: 'error',
      body: JSON.stringify({ version_code: versionCode, purpose, idempotency_key: crypto.randomUUID() }),
    })
  },

  // Admin endpoints
  async getAdminApps(): Promise<AppsResponse> {
    return request('/api/admin/apps')
  },

  async getAdminApp(packageName: string): Promise<App> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}`)
  },

  async getAdminUsers(): Promise<{ users: KnownUser[] }> {
    return request('/api/admin/users')
  },

  async getUserAccess(subject: string): Promise<UserAccess> {
    return request(`/api/admin/users/${encodeURIComponent(subject)}/access`)
  },

  async setDirectGrant(subject: string, packageName: string, accessLevel: AccessLevel): Promise<void> {
    return request(`/api/admin/users/${encodeURIComponent(subject)}/apps/${encodeURIComponent(packageName)}`, {
      method: 'PUT',
      body: JSON.stringify({ access_level: accessLevel }),
    })
  },

  async removeDirectGrant(subject: string, packageName: string): Promise<void> {
    return request(`/api/admin/users/${encodeURIComponent(subject)}/apps/${encodeURIComponent(packageName)}`, {
      method: 'DELETE',
    })
  },

  async getAppGroups(): Promise<{ groups: AppGroup[] }> {
    return request('/api/admin/app-groups')
  },

  async createAppGroup(name: string): Promise<Omit<AppGroup, 'grants' | 'user_subjects'>> {
    return request('/api/admin/app-groups', {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
  },

  async renameAppGroup(groupId: number, name: string): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    })
  },

  async deleteAppGroup(groupId: number): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}`, { method: 'DELETE' })
  },

  async setGroupGrant(groupId: number, packageName: string, accessLevel: AccessLevel): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}/apps/${encodeURIComponent(packageName)}`, {
      method: 'PUT',
      body: JSON.stringify({ access_level: accessLevel }),
    })
  },

  async removeGroupGrant(groupId: number, packageName: string): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}/apps/${encodeURIComponent(packageName)}`, {
      method: 'DELETE',
    })
  },

  async addGroupMember(groupId: number, subject: string): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}/users/${encodeURIComponent(subject)}`, {
      method: 'PUT',
    })
  },

  async removeGroupMember(groupId: number, subject: string): Promise<void> {
    return request(`/api/admin/app-groups/${groupId}/users/${encodeURIComponent(subject)}`, {
      method: 'DELETE',
    })
  },

  async setReleaseChannel(packageName: string, versionCode: number, isBeta: boolean): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/versions/${versionCode}`, {
      method: 'PUT',
      body: JSON.stringify({ is_beta: isBeta }),
    })
  },

  async uploadApp(
    file: File,
    name?: string,
    description?: string,
    isBeta = false
  ): Promise<UploadResponse> {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('publication', 'draft')
    formData.append('distribution_mode', 'normal')
    if (name) formData.append('name', name)
    if (description) formData.append('description', description)
    formData.append('is_beta', String(isBeta))

    let job = await request<UploadJob>('/api/admin/apps?asynchronous=true', {
      method: 'POST', body: formData,
    })
    const id = job.id
    const deadline = Date.now() + 10 * 60 * 1000
    while (job.status === 'queued' || job.status === 'validating') {
      if (Date.now() > deadline) throw new Error(`Upload ${id} is still validating. Check Uploads for its result.`)
      await new Promise(resolve => setTimeout(resolve, 1000))
      job = await request<UploadJob>(`/api/admin/uploads/${encodeURIComponent(id)}`)
    }
    if (job.status === 'failed') throw new Error(`Upload ${id}: ${job.error || 'Validation failed'}. See Uploads to retry.`)
    const identity = JSON.parse(job.result_json || '{}') as { package_name: string; version_code: number }
    const app = await api.getAdminApp(identity.package_name)
    const version = app.versions.find(v => v.version_code === identity.version_code)
    if (!version) throw new Error('The validated draft was removed. Check Uploads for details.')
    return { package_name: app.package_name, name: app.name, description: app.description, icon_url: app.icon_url, version }

  },

  async getAppDistribution(packageName: string): Promise<AppDistribution> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/distribution`)
  },
  async uploadVpk(packageName: string, contract: string, file: File): Promise<UploadJob> {
    const body = new FormData(); body.append('file', file)
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/contracts/${encodeURIComponent(contract)}/vpks`, { method: 'POST', body })
  },
  async publishVpk(packageName: string, id: string, revision: number, withdraw = false): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/vpks/${encodeURIComponent(id)}/${withdraw ? 'withdraw' : 'publish'}`, { method: 'POST', body: JSON.stringify({ expected_revision: revision }) })
  },
  async saveVpkNotes(packageName: string, id: string, revision: number, notes: string): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/vpks/${encodeURIComponent(id)}/notes`, { method: 'PUT', body: JSON.stringify({ expected_revision: revision, release_notes: notes }) })
  },
  async setParavoidStream(packageName: string, contract: string, revision: number, retired: boolean): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/streams/${encodeURIComponent(contract)}`, { method: 'PUT', body: JSON.stringify({ expected_revision: revision, retired }) })
  },
  async revokeParavoidGrant(packageName: string, id: string, revision: number): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/grants/${encodeURIComponent(id)}/revoke`, { method: 'POST', body: JSON.stringify({ expected_revision: revision }) })
  },
  async downloadVpk(packageName: string, release: VpkRelease): Promise<Blob> {
    const blob = await request<Blob>(`/api/admin/apps/${encodeURIComponent(packageName)}/vpks/${encodeURIComponent(release.id)}/file`, { redirect: 'error' }, false, response => response.blob())
    const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
    const hash = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
    if (blob.size !== release.archive_size || hash !== release.archive_sha256) throw new Error('VPK checksum verification failed')
    return blob
  },

  async getParavoidConfiguration(): Promise<ParavoidConfiguration> {
    return request('/api/admin/paravoid/configuration')
  },

  async getUploads(): Promise<UploadJob[]> {
    return request('/api/admin/uploads')
  },

  async retryUpload(id: string): Promise<UploadJob> {
    return request(`/api/admin/uploads/${encodeURIComponent(id)}/retry`, { method: 'POST' })
  },

  getDistributionReviews(packageName: string): Promise<DistributionReview[]> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/distribution-reviews`)
  },

  async reviewDistributionTransition(packageName: string, versionCode: number, expectedRevision: number, migration: { tested_upgrade: boolean; database_preserved: boolean; authentication_preserved: boolean; files_preserved: boolean; evidence: string }): Promise<{ id: string; publication_revision: number; signer_sha256: string }> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/distribution-reviews`, {
      method: 'POST', body: JSON.stringify({ version_code: versionCode, expected_revision: expectedRevision, migration }),
    })
  },

  async publishRelease(packageName: string, versionCode: number, expectedRevision: number, replaceLatest = false, transitionReview?: string): Promise<PublicationResult> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/publications`, {
      method: 'POST', body: JSON.stringify({ version_code: versionCode, expected_revision: expectedRevision, replace_latest: replaceLatest, transition_review: transitionReview }),
    })
  },

  async withdrawRelease(packageName: string, versionCode: number, expectedRevision: number): Promise<PublicationResult> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/versions/${versionCode}/withdraw`, {
      method: 'POST', body: JSON.stringify({ expected_revision: expectedRevision }),
    })
  },

  async saveDraft(packageName: string, versionCode: number, releaseNotes: string, isBeta: boolean): Promise<void> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/versions/${versionCode}/draft`, {
      method: 'PUT', body: JSON.stringify({ release_notes: releaseNotes, is_beta: isBeta }),
    })
  },

  async getPublicationHistory(packageName: string): Promise<PublicationEvent[]> {
    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/publications`)
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

  async uploadIcon(
    packageName: string,
    file: File
  ): Promise<{ message: string; icon_url: string }> {
    const formData = new FormData()
    formData.append('file', file)

    return request(`/api/admin/apps/${encodeURIComponent(packageName)}/icon`, {
      method: 'POST',
      body: formData,
    })
  },
}
