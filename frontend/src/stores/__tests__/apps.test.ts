import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppsStore } from '../apps'
import { api } from '@/services/api'

// Mock the API module
vi.mock('@/services/api', () => ({
  api: {
    getApps: vi.fn(),
    getApp: vi.fn(),
    uploadApp: vi.fn(),
    updateApp: vi.fn(),
    deleteApp: vi.fn(),
    deleteVersion: vi.fn(),
  },
}))

describe('Apps Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('fetchApps', () => {
    it('fetches apps from API and updates state', async () => {
      const mockApps = [
        { packageName: 'com.test.app', name: 'Test App', iconUrl: '/api/apps/com.test.app/icon', latestVersion: { versionCode: 1, versionName: '1.0', size: 1000 } },
        { packageName: 'com.test.app2', name: 'Test App 2', iconUrl: '/api/apps/com.test.app2/icon' },
      ]
      vi.mocked(api.getApps).mockResolvedValue({ apps: mockApps })

      const store = useAppsStore()
      await store.fetchApps()

      expect(api.getApps).toHaveBeenCalled()
      expect(store.apps).toEqual(mockApps)
      expect(store.isLoading).toBe(false)
      expect(store.error).toBeNull()
    })

    it('sets loading state during fetch', async () => {
      vi.mocked(api.getApps).mockImplementation(() => new Promise(() => {})) // Never resolves

      const store = useAppsStore()
      store.fetchApps() // Don't await - just trigger the fetch

      expect(store.isLoading).toBe(true)
    })

    it('handles fetch error', async () => {
      vi.mocked(api.getApps).mockRejectedValue(new Error('Network error'))

      const store = useAppsStore()
      await expect(store.fetchApps()).rejects.toThrow()

      expect(store.error).toBe('Network error')
      expect(store.isLoading).toBe(false)
    })
  })

  describe('fetchApp', () => {
    it('fetches single app and updates currentApp', async () => {
      const mockApp = {
        packageName: 'com.test.app',
        name: 'Test App',
        iconUrl: '/api/apps/com.test.app/icon',
        versions: [],
      }
      vi.mocked(api.getApp).mockResolvedValue(mockApp)

      const store = useAppsStore()
      await store.fetchApp('com.test.app')

      expect(api.getApp).toHaveBeenCalledWith('com.test.app')
      expect(store.currentApp).toEqual(mockApp)
      expect(store.isLoading).toBe(false)
    })
  })

  describe('uploadApp', () => {
    it('uploads app and refreshes list', async () => {
      const mockResponse = {
        packageName: 'com.new.app',
        name: 'New App',
        iconUrl: '',
        version: {
          versionCode: 1,
          versionName: '1.0.0',
          size: 1000,
          sha256: '0'.repeat(64),
          minSdk: 21,
          uploadedAt: '2024-01-01T00:00:00Z',
          apkUrl: '/api/apps/com.new.app/versions/1/apk',
        },
      }
      vi.mocked(api.uploadApp).mockResolvedValue(mockResponse)
      vi.mocked(api.getApps).mockResolvedValue({ apps: [] })

      const store = useAppsStore()
      const file = new File(['test'], 'test.apk', { type: 'application/vnd.android.package-archive' })
      const result = await store.uploadApp(file, 'Custom Name', 'Description')

      expect(api.uploadApp).toHaveBeenCalledWith(file, 'Custom Name', 'Description')
      expect(api.getApps).toHaveBeenCalled()
      expect(result).toEqual(mockResponse)
      expect(store.isUploading).toBe(false)
    })

    it('sets uploading state during upload', async () => {
      vi.mocked(api.uploadApp).mockImplementation(() => new Promise(() => {}))

      const store = useAppsStore()
      const file = new File(['test'], 'test.apk')
      store.uploadApp(file)

      expect(store.isUploading).toBe(true)
    })
  })

  describe('updateApp', () => {
    it('updates app and local state', async () => {
      const initialApp = { packageName: 'com.test.app', name: 'Old Name', iconUrl: '' }
      const updatedApp = { packageName: 'com.test.app', name: 'New Name', iconUrl: '', versions: [] }

      vi.mocked(api.getApps).mockResolvedValue({ apps: [initialApp] })
      vi.mocked(api.updateApp).mockResolvedValue(updatedApp)

      const store = useAppsStore()
      await store.fetchApps()
      await store.updateApp('com.test.app', { name: 'New Name' })

      expect(api.updateApp).toHaveBeenCalledWith('com.test.app', { name: 'New Name' })
      expect(store.apps[0].name).toBe('New Name')
    })
  })

  describe('deleteApp', () => {
    it('deletes app and removes from local state', async () => {
      const mockApps = [
        { packageName: 'com.test.app', name: 'Test App', iconUrl: '' },
        { packageName: 'com.test.app2', name: 'Test App 2', iconUrl: '' },
      ]

      vi.mocked(api.getApps).mockResolvedValue({ apps: mockApps })
      vi.mocked(api.deleteApp).mockResolvedValue(undefined)

      const store = useAppsStore()
      await store.fetchApps()
      expect(store.apps).toHaveLength(2)

      await store.deleteApp('com.test.app')

      expect(api.deleteApp).toHaveBeenCalledWith('com.test.app')
      expect(store.apps).toHaveLength(1)
      expect(store.apps[0].packageName).toBe('com.test.app2')
    })

    it('clears currentApp if deleted', async () => {
      const mockApp = { packageName: 'com.test.app', name: 'Test App', iconUrl: '', versions: [] }

      vi.mocked(api.getApp).mockResolvedValue(mockApp)
      vi.mocked(api.deleteApp).mockResolvedValue(undefined)

      const store = useAppsStore()
      await store.fetchApp('com.test.app')
      expect(store.currentApp).not.toBeNull()

      await store.deleteApp('com.test.app')

      expect(store.currentApp).toBeNull()
    })
  })

  describe('deleteVersion', () => {
    it('deletes version and refreshes app', async () => {
      const mockApp = {
        packageName: 'com.test.app',
        name: 'Test App',
        iconUrl: '',
        versions: [{
          versionCode: 1,
          versionName: '1.0.0',
          size: 1000,
          sha256: '0'.repeat(64),
          minSdk: 21,
          uploadedAt: '2024-01-01T00:00:00Z',
          apkUrl: '/api/apps/com.test.app/versions/1/apk',
        }],
      }

      vi.mocked(api.deleteVersion).mockResolvedValue(undefined)
      vi.mocked(api.getApp).mockResolvedValue(mockApp)

      const store = useAppsStore()
      await store.deleteVersion('com.test.app', 1)

      expect(api.deleteVersion).toHaveBeenCalledWith('com.test.app', 1)
      expect(api.getApp).toHaveBeenCalledWith('com.test.app')
    })
  })

  describe('sortedApps getter', () => {
    it('returns apps sorted by name', async () => {
      const mockApps = [
        { packageName: 'com.z.app', name: 'Zebra', iconUrl: '' },
        { packageName: 'com.a.app', name: 'Alpha', iconUrl: '' },
        { packageName: 'com.m.app', name: 'Middle', iconUrl: '' },
      ]

      vi.mocked(api.getApps).mockResolvedValue({ apps: mockApps })

      const store = useAppsStore()
      await store.fetchApps()

      expect(store.sortedApps[0].name).toBe('Alpha')
      expect(store.sortedApps[1].name).toBe('Middle')
      expect(store.sortedApps[2].name).toBe('Zebra')
    })
  })

  describe('clearError', () => {
    it('clears error state', async () => {
      vi.mocked(api.getApps).mockRejectedValue(new Error('Network error'))

      const store = useAppsStore()
      await expect(store.fetchApps()).rejects.toThrow()

      expect(store.error).toBe('Network error')

      store.clearError()

      expect(store.error).toBeNull()
    })
  })
})
