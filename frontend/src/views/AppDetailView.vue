<template>
  <DefaultLayout>
    <!-- Loading -->
    <v-skeleton-loader v-if="appsStore.isLoading || !hasFetched" type="article, table" />

    <!-- Not found -->
    <v-alert v-else-if="!app" type="error" variant="tonal">
      <v-alert-title>App not found</v-alert-title>
      The requested application could not be found.
      <template #append>
        <v-btn variant="text" @click="router.push({ name: 'apps' })">
          Back to Apps
        </v-btn>
      </template>
    </v-alert>

    <!-- App details -->
    <template v-else>
      <!-- Header -->
      <div class="d-flex align-center mb-6">
        <v-btn icon variant="text" @click="router.push({ name: 'apps' })" class="mr-2">
          <v-icon>mdi-arrow-left</v-icon>
        </v-btn>
        <v-avatar size="64" rounded class="mr-4">
          <AuthenticatedImg :src="api.getIconUrl(app.package_name)">
            <template #fallback>
              <v-icon icon="mdi-android" size="32" />
            </template>
          </AuthenticatedImg>
        </v-avatar>
        <div class="flex-grow-1">
          <h1 class="text-h4">{{ app.name }}</h1>
          <p class="text-body-2 text-medium-emphasis">{{ app.package_name }}</p>
        </div>
        <v-btn v-if="authStore.isAdmin" variant="outlined" class="mr-2" @click="showEditDialog = true">
          <v-icon start>mdi-pencil</v-icon>
          Edit
        </v-btn>
        <v-btn v-if="authStore.isAdmin" color="error" variant="outlined" @click="showDeleteAppDialog = true">
          <v-icon start>mdi-delete</v-icon>
          Delete
        </v-btn>
      </div>

      <!-- Description -->
      <v-card class="mb-6" v-if="app.description">
        <v-card-title>Description</v-card-title>
        <v-card-text>{{ app.description }}</v-card-text>
      </v-card>

      <!-- Versions -->
      <v-card>
        <v-card-title class="d-flex align-center">
          Versions
          <v-spacer />
          <v-btn v-if="authStore.isAdmin" size="small" color="primary" @click="showUploadDialog = true">
            <v-icon start>mdi-upload</v-icon>
            Upload Version
          </v-btn>
        </v-card-title>
        <v-data-table
          :headers="versionHeaders"
          :items="app.versions || []"
          item-key="version_code"
        >
          <template #item.size="{ item }">
            {{ formatSize(item.size) }}
          </template>

          <template #item.uploaded_at="{ item }">
            {{ formatDate(item.uploaded_at) }}
          </template>

          <template #item.actions="{ item }">
            <v-btn
              v-if="authStore.isAdmin"
              icon
              size="small"
              variant="text"
              @click="downloadVersion(item)"
              title="Download APK"
            >
              <v-icon>mdi-download</v-icon>
            </v-btn>
            <v-btn
              icon
              size="small"
              variant="text"
              color="error"
              @click="confirmDeleteVersion(item)"
              title="Delete version"
            >
              <v-icon>mdi-delete</v-icon>
            </v-btn>
          </template>
        </v-data-table>
      </v-card>
    </template>

    <!-- Dialogs -->
    <EditAppDialog
      v-if="authStore.isAdmin"
      v-model="showEditDialog"
      :app="app"
      @saved="onAppUpdated"
    />
    <UploadDialog
      v-if="authStore.isAdmin"
      v-model="showUploadDialog"
      @uploaded="onVersionUploaded"
    />
    <ConfirmDialog
      v-if="authStore.isAdmin"
      v-model="showDeleteAppDialog"
      title="Delete Application"
      :message="`Are you sure you want to delete '${app?.name}'? This will remove all versions and cannot be undone.`"
      confirm-text="Delete"
      confirm-color="error"
      @confirm="deleteApp"
    />
    <ConfirmDialog
      v-if="authStore.isAdmin"
      v-model="showDeleteVersionDialog"
      title="Delete Version"
      :message="deleteVersionMessage"
      confirm-text="Delete"
      confirm-color="error"
      @confirm="deleteVersion"
    />
  </DefaultLayout>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import EditAppDialog from '@/components/EditAppDialog.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import AuthenticatedImg from '@/components/AuthenticatedImg.vue'
import { useAppsStore } from '@/stores/apps'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { api, type AppVersion } from '@/services/api'

const route = useRoute()
const router = useRouter()
const appsStore = useAppsStore()
const authStore = useAuthStore()
const toast = useToast()

const showEditDialog = ref(false)
const showUploadDialog = ref(false)
const showDeleteAppDialog = ref(false)
const showDeleteVersionDialog = ref(false)
const versionToDelete = ref<AppVersion | null>(null)
const hasFetched = ref(false)

const packageName = computed(() => route.params.packageName as string)
const app = computed(() => appsStore.currentApp)

const versionHeaders = [
  { title: 'Version', key: 'version_name' },
  { title: 'Code', key: 'version_code' },
  { title: 'Size', key: 'size' },
  { title: 'Min SDK', key: 'min_sdk' },
  { title: 'Uploaded', key: 'uploaded_at' },
  { title: 'Actions', key: 'actions', sortable: false, align: 'end' as const },
]

const deleteVersionMessage = computed(() => {
  if (!versionToDelete.value || !app.value) return ''
  const isLastVersion = (app.value.versions?.length || 0) === 1
  if (isLastVersion) {
    return `This is the last version. Deleting it will also delete the entire app '${app.value.name}'.`
  }
  return `Are you sure you want to delete version ${versionToDelete.value.version_name}?`
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString()
}

function confirmDeleteVersion(version: AppVersion) {
  versionToDelete.value = version
  showDeleteVersionDialog.value = true
}

async function deleteApp() {
  try {
    await appsStore.deleteApp(packageName.value)
    toast.success('App deleted successfully')
    router.push({ name: 'apps' })
  } catch {
    toast.error('Failed to delete app')
  }
}

async function deleteVersion() {
  if (!versionToDelete.value) return
  try {
    await appsStore.deleteVersion(packageName.value, versionToDelete.value.version_code)
    toast.success('Version deleted successfully')
    // Check if app was also deleted (last version)
    if (!appsStore.currentApp) {
      router.push({ name: 'apps' })
    }
  } catch {
    toast.error('Failed to delete version')
  }
}

async function downloadVersion(version: AppVersion) {
  if (!app.value) return

  try {
    const blob = await api.downloadApk(app.value.package_name, version.version_code)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${app.value.package_name}-${version.version_name}.apk`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    toast.error('Failed to download APK')
  }
}

function onAppUpdated() {
  showEditDialog.value = false
  toast.success('App updated successfully')
}

function onVersionUploaded() {
  showUploadDialog.value = false
  toast.success('Version uploaded successfully')
  appsStore.fetchApp(packageName.value)
}

watch(packageName, async (name) => {
  if (name) {
    hasFetched.value = false
    try {
      await appsStore.fetchApp(name)
    } catch {
      // The store exposes the fetch error and leaves currentApp empty.
    } finally {
      hasFetched.value = true
    }
  }
}, { immediate: true })
</script>
