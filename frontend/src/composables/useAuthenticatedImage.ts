import { ref, watch, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * Composable to fetch images with authentication headers.
 * Returns a blob URL that can be used in <img> src.
 */
export function useAuthenticatedImage(urlGetter: () => string | undefined) {
  const blobUrl = ref<string | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const authStore = useAuthStore()

  async function fetchImage() {
    const url = urlGetter()
    if (!url) {
      blobUrl.value = null
      return
    }

    // Revoke old blob URL to prevent memory leaks
    if (blobUrl.value) {
      URL.revokeObjectURL(blobUrl.value)
      blobUrl.value = null
    }

    isLoading.value = true
    error.value = null

    try {
      const headers: HeadersInit = {}
      if (authStore.accessToken) {
        headers['Authorization'] = `Bearer ${authStore.accessToken}`
      }

      const response = await fetch(url, { headers })

      if (!response.ok) {
        if (response.status === 404) {
          // No icon available, not an error
          blobUrl.value = null
          return
        }
        throw new Error(`Failed to load image: ${response.status}`)
      }

      const blob = await response.blob()
      blobUrl.value = URL.createObjectURL(blob)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load image'
      blobUrl.value = null
    } finally {
      isLoading.value = false
    }
  }

  // Watch for URL changes and refetch
  watch(urlGetter, fetchImage, { immediate: true })

  // Cleanup blob URL on unmount
  onUnmounted(() => {
    if (blobUrl.value) {
      URL.revokeObjectURL(blobUrl.value)
    }
  })

  return {
    blobUrl,
    isLoading,
    error,
    refresh: fetchImage,
  }
}
