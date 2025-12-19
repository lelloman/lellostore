import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api, type App, type AppListItem } from '@/services/api'

export const useAppsStore = defineStore('apps', () => {
  // State
  const apps = ref<AppListItem[]>([])
  const currentApp = ref<App | null>(null)
  const isLoading = ref(false)
  const isUploading = ref(false)
  const error = ref<string | null>(null)

  // Getters
  const appCount = computed(() => apps.value.length)
  const sortedApps = computed(() =>
    [...apps.value].sort((a, b) => a.name.localeCompare(b.name))
  )

  // Actions
  async function fetchApps() {
    isLoading.value = true
    error.value = null
    try {
      const response = await api.getApps()
      apps.value = response.apps
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch apps'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function fetchApp(packageName: string) {
    isLoading.value = true
    error.value = null
    try {
      currentApp.value = await api.getApp(packageName)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch app'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function uploadApp(file: File, name?: string, description?: string) {
    isUploading.value = true
    error.value = null
    try {
      const response = await api.uploadApp(file, name, description)
      // Refresh apps list to include new app
      await fetchApps()
      return response
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to upload app'
      throw e
    } finally {
      isUploading.value = false
    }
  }

  async function updateApp(packageName: string, data: { name?: string; description?: string }) {
    error.value = null
    try {
      const updated = await api.updateApp(packageName, data)
      // Update in local state - list items only have basic fields
      const index = apps.value.findIndex(a => a.packageName === packageName)
      if (index >= 0) {
        apps.value[index] = {
          ...apps.value[index],
          name: updated.name,
          description: updated.description,
        }
      }
      if (currentApp.value?.packageName === packageName) {
        currentApp.value = updated
      }
      return updated
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update app'
      throw e
    }
  }

  async function deleteApp(packageName: string) {
    error.value = null
    try {
      await api.deleteApp(packageName)
      // Remove from local state
      apps.value = apps.value.filter(a => a.packageName !== packageName)
      if (currentApp.value?.packageName === packageName) {
        currentApp.value = null
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete app'
      throw e
    }
  }

  async function deleteVersion(packageName: string, versionCode: number) {
    error.value = null
    try {
      await api.deleteVersion(packageName, versionCode)
      // Refresh current app to update version list
      // If this was the last version, the app is also deleted and fetchApp will 404
      try {
        await fetchApp(packageName)
      } catch {
        // App was deleted (last version), remove from local state
        apps.value = apps.value.filter(a => a.packageName !== packageName)
        currentApp.value = null
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete version'
      throw e
    }
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    apps,
    currentApp,
    isLoading,
    isUploading,
    error,
    // Getters
    appCount,
    sortedApps,
    // Actions
    fetchApps,
    fetchApp,
    uploadApp,
    updateApp,
    deleteApp,
    deleteVersion,
    clearError,
  }
})
