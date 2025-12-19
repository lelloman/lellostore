# Epic 6: Frontend Features - Implementation Plan

**Goal**: Implement the admin dashboard with full CRUD functionality for managing apps.

**Dependencies**: Epic 5 (Frontend Foundation) ✅

**Status**: In Progress

---

## Task Overview

| # | Task | Status |
|---|------|--------|
| 1 | Apps Store (Pinia) | done |
| 2 | Apps List View | done |
| 3 | App Detail View | done |
| 4 | Upload Dialog | done |
| 5 | Edit App Dialog | done |
| 6 | Delete Confirmation Dialogs | done |
| 7 | Toast Notifications | done |
| 8 | Loading States & Skeletons | done |
| 9 | Error Handling | done |
| 10 | Router Updates | done |
| 11 | Dashboard Integration | done |
| 12 | Component Tests | un-done |

---

## Task 1: Apps Store (Pinia)

**Status**: done

### Description

Create a Pinia store to manage the apps state. This store will:
- Hold the list of apps fetched from the API
- Provide actions for CRUD operations (fetch, upload, update, delete)
- Track loading and error states
- Cache app data to avoid unnecessary refetches

The store acts as a single source of truth for app data across all components.

### Implementation

Create `src/stores/apps.ts`:

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api, type App, type AppsResponse, type UploadResponse } from '@/services/api'

export const useAppsStore = defineStore('apps', () => {
  // State
  const apps = ref<App[]>([])
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
      // Also update in apps list if present
      const index = apps.value.findIndex(a => a.packageName === packageName)
      if (index >= 0) {
        apps.value[index] = currentApp.value
      }
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
      // Update in local state
      const index = apps.value.findIndex(a => a.packageName === packageName)
      if (index >= 0) {
        apps.value[index] = { ...apps.value[index], ...updated }
      }
      if (currentApp.value?.packageName === packageName) {
        currentApp.value = { ...currentApp.value, ...updated }
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
      await fetchApp(packageName)
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
```

### Acceptance Criteria

- [ ] Apps store created with all state and actions
- [ ] Store correctly wraps API calls
- [ ] Loading states tracked for async operations
- [ ] Error states captured and exposed
- [ ] Local state updated after mutations

---

## Task 2: Apps List View

**Status**: done

### Description

Create the main apps list view that displays all apps in the catalog. This is the primary view users see after logging in. Features include:
- Data table with sortable columns
- App icon, name, package name, latest version, upload date
- Search/filter functionality
- Empty state when no apps exist
- Click to navigate to app detail
- Upload button in toolbar

### Implementation

Create `src/views/AppsListView.vue`:

```vue
<template>
  <DefaultLayout>
    <div class="d-flex justify-space-between align-center mb-4">
      <h1 class="text-h4">Applications</h1>
      <v-btn color="primary" @click="showUploadDialog = true">
        <v-icon start>mdi-upload</v-icon>
        Upload App
      </v-btn>
    </div>

    <!-- Search -->
    <v-text-field
      v-model="search"
      prepend-inner-icon="mdi-magnify"
      label="Search apps..."
      single-line
      hide-details
      clearable
      class="mb-4"
    />

    <!-- Loading state -->
    <v-skeleton-loader v-if="appsStore.isLoading" type="table" />

    <!-- Empty state -->
    <v-card v-else-if="appsStore.apps.length === 0" class="text-center pa-8">
      <v-icon size="64" color="grey" class="mb-4">mdi-package-variant</v-icon>
      <h3 class="text-h6 mb-2">No applications yet</h3>
      <p class="text-body-2 text-medium-emphasis mb-4">
        Upload your first APK or AAB to get started.
      </p>
      <v-btn color="primary" @click="showUploadDialog = true">
        <v-icon start>mdi-upload</v-icon>
        Upload App
      </v-btn>
    </v-card>

    <!-- Apps table -->
    <v-data-table
      v-else
      :headers="headers"
      :items="filteredApps"
      :search="search"
      item-key="packageName"
      hover
      @click:row="(_event, { item }) => goToApp(item)"
      class="cursor-pointer"
    >
      <template #item.icon="{ item }">
        <v-avatar size="40" rounded>
          <v-img :src="getIconUrl(item.packageName)" />
        </v-avatar>
      </template>

      <template #item.latestVersion="{ item }">
        <span v-if="item.latestVersion">
          {{ item.latestVersion.versionName }}
          <span class="text-medium-emphasis">({{ item.latestVersion.versionCode }})</span>
        </span>
        <span v-else class="text-medium-emphasis">—</span>
      </template>

      <template #item.size="{ item }">
        {{ item.latestVersion ? formatSize(item.latestVersion.size) : '—' }}
      </template>
    </v-data-table>

    <!-- Upload Dialog -->
    <UploadDialog v-model="showUploadDialog" @uploaded="onUploaded" />
  </DefaultLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import { useAppsStore } from '@/stores/apps'
import { api } from '@/services/api'

const router = useRouter()
const appsStore = useAppsStore()

const search = ref('')
const showUploadDialog = ref(false)

const headers = [
  { title: '', key: 'icon', sortable: false, width: '60px' },
  { title: 'Name', key: 'name' },
  { title: 'Package', key: 'packageName' },
  { title: 'Version', key: 'latestVersion', sortable: false },
  { title: 'Size', key: 'size', sortable: false },
]

const filteredApps = computed(() => appsStore.sortedApps)

function getIconUrl(packageName: string) {
  return api.getIconUrl(packageName)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function goToApp(app: { packageName: string }) {
  router.push({ name: 'app-detail', params: { packageName: app.packageName } })
}

function onUploaded() {
  showUploadDialog.value = false
}

onMounted(() => {
  appsStore.fetchApps()
})
</script>

<style scoped>
.cursor-pointer :deep(tbody tr) {
  cursor: pointer;
}
</style>
```

### Key Features

- **Data Table**: Vuetify's `v-data-table` with custom column templates
- **Search**: Client-side filtering with search field
- **Empty State**: Friendly message when no apps exist
- **Loading State**: Skeleton loader while fetching
- **Row Click**: Navigate to app detail on row click

### Acceptance Criteria

- [ ] Apps displayed in sortable data table
- [ ] Icons load correctly from API
- [ ] Search filters apps by name/package
- [ ] Empty state shown when no apps
- [ ] Loading skeleton shown during fetch
- [ ] Click row navigates to detail view
- [ ] Upload button opens dialog

---

## Task 3: App Detail View

**Status**: done

### Description

Create the app detail view that shows full information about an app and its versions. Features include:
- App icon, name, description, package name
- Version history table with download links
- Edit button to modify metadata
- Delete buttons for app and individual versions
- Back navigation to list

### Implementation

Create `src/views/AppDetailView.vue`:

```vue
<template>
  <DefaultLayout>
    <!-- Loading -->
    <v-skeleton-loader v-if="appsStore.isLoading" type="article, table" />

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
          <v-img :src="api.getIconUrl(app.packageName)" />
        </v-avatar>
        <div class="flex-grow-1">
          <h1 class="text-h4">{{ app.name }}</h1>
          <p class="text-body-2 text-medium-emphasis">{{ app.packageName }}</p>
        </div>
        <v-btn variant="outlined" class="mr-2" @click="showEditDialog = true">
          <v-icon start>mdi-pencil</v-icon>
          Edit
        </v-btn>
        <v-btn color="error" variant="outlined" @click="showDeleteAppDialog = true">
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
          <v-btn size="small" color="primary" @click="showUploadDialog = true">
            <v-icon start>mdi-upload</v-icon>
            Upload Version
          </v-btn>
        </v-card-title>
        <v-data-table
          :headers="versionHeaders"
          :items="app.versions || []"
          item-key="versionCode"
        >
          <template #item.size="{ item }">
            {{ formatSize(item.size) }}
          </template>

          <template #item.uploadedAt="{ item }">
            {{ formatDate(item.uploadedAt) }}
          </template>

          <template #item.actions="{ item }">
            <v-btn
              icon
              size="small"
              variant="text"
              :href="item.apkUrl"
              download
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
      v-model="showEditDialog"
      :app="app"
      @saved="onAppUpdated"
    />
    <UploadDialog
      v-model="showUploadDialog"
      @uploaded="onVersionUploaded"
    />
    <ConfirmDialog
      v-model="showDeleteAppDialog"
      title="Delete Application"
      :message="`Are you sure you want to delete '${app?.name}'? This will remove all versions and cannot be undone.`"
      confirm-text="Delete"
      confirm-color="error"
      @confirm="deleteApp"
    />
    <ConfirmDialog
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
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import EditAppDialog from '@/components/EditAppDialog.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useAppsStore } from '@/stores/apps'
import { useToast } from '@/composables/useToast'
import { api, type AppVersion } from '@/services/api'

const route = useRoute()
const router = useRouter()
const appsStore = useAppsStore()
const toast = useToast()

const showEditDialog = ref(false)
const showUploadDialog = ref(false)
const showDeleteAppDialog = ref(false)
const showDeleteVersionDialog = ref(false)
const versionToDelete = ref<AppVersion | null>(null)

const packageName = computed(() => route.params.packageName as string)
const app = computed(() => appsStore.currentApp)

const versionHeaders = [
  { title: 'Version', key: 'versionName' },
  { title: 'Code', key: 'versionCode' },
  { title: 'Size', key: 'size' },
  { title: 'Min SDK', key: 'minSdk' },
  { title: 'Uploaded', key: 'uploadedAt' },
  { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
]

const deleteVersionMessage = computed(() => {
  if (!versionToDelete.value || !app.value) return ''
  const isLastVersion = (app.value.versions?.length || 0) === 1
  if (isLastVersion) {
    return `This is the last version. Deleting it will also delete the entire app '${app.value.name}'.`
  }
  return `Are you sure you want to delete version ${versionToDelete.value.versionName}?`
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return '—'
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
    await appsStore.deleteVersion(packageName.value, versionToDelete.value.versionCode)
    toast.success('Version deleted successfully')
    // Check if app was also deleted (last version)
    if (!appsStore.currentApp) {
      router.push({ name: 'apps' })
    }
  } catch {
    toast.error('Failed to delete version')
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

watch(packageName, (name) => {
  if (name) appsStore.fetchApp(name)
}, { immediate: true })
</script>
```

### Acceptance Criteria

- [ ] App details displayed with icon
- [ ] Description shown if present
- [ ] Version history table with all version info
- [ ] Download button for each version
- [ ] Delete buttons for app and versions
- [ ] Edit button opens edit dialog
- [ ] Back button returns to list
- [ ] Handles not found state

---

## Task 4: Upload Dialog

**Status**: done

### Description

Create a reusable upload dialog component for uploading APK/AAB files. Features include:
- Drag-and-drop zone
- File picker fallback
- File type validation (APK/AAB only)
- Optional name/description fields
- Upload progress indicator
- Success/error feedback

### Implementation

Create `src/components/UploadDialog.vue`:

```vue
<template>
  <v-dialog v-model="model" max-width="500" persistent>
    <v-card>
      <v-card-title>Upload Application</v-card-title>

      <v-card-text>
        <!-- Drop zone -->
        <div
          class="drop-zone pa-8 text-center rounded-lg mb-4"
          :class="{ 'drop-zone--active': isDragOver, 'drop-zone--has-file': selectedFile }"
          @dragover.prevent="isDragOver = true"
          @dragleave="isDragOver = false"
          @drop.prevent="onDrop"
          @click="openFilePicker"
        >
          <input
            ref="fileInput"
            type="file"
            accept=".apk,.aab"
            hidden
            @change="onFileSelected"
          />

          <template v-if="selectedFile">
            <v-icon size="48" color="success" class="mb-2">mdi-check-circle</v-icon>
            <p class="text-body-1 font-weight-medium">{{ selectedFile.name }}</p>
            <p class="text-body-2 text-medium-emphasis">{{ formatSize(selectedFile.size) }}</p>
            <v-btn variant="text" size="small" @click.stop="clearFile">Change file</v-btn>
          </template>

          <template v-else>
            <v-icon size="48" color="grey" class="mb-2">mdi-cloud-upload</v-icon>
            <p class="text-body-1">Drag and drop APK or AAB file here</p>
            <p class="text-body-2 text-medium-emphasis">or click to browse</p>
          </template>
        </div>

        <!-- Optional fields -->
        <v-text-field
          v-model="appName"
          label="App Name (optional)"
          hint="Override the name extracted from the APK"
          persistent-hint
          class="mb-4"
        />

        <v-textarea
          v-model="description"
          label="Description (optional)"
          rows="3"
        />

        <!-- Upload progress -->
        <v-progress-linear
          v-if="isUploading"
          indeterminate
          color="primary"
          class="mt-4"
        />

        <!-- Error -->
        <v-alert v-if="error" type="error" variant="tonal" class="mt-4">
          {{ error }}
        </v-alert>
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isUploading" @click="close">
          Cancel
        </v-btn>
        <v-btn
          color="primary"
          :disabled="!selectedFile || isUploading"
          :loading="isUploading"
          @click="upload"
        >
          Upload
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAppsStore } from '@/stores/apps'

const model = defineModel<boolean>()
const emit = defineEmits<{
  uploaded: [void]
}>()

const appsStore = useAppsStore()
const fileInput = ref<HTMLInputElement>()

const selectedFile = ref<File | null>(null)
const appName = ref('')
const description = ref('')
const isDragOver = ref(false)
const isUploading = ref(false)
const error = ref<string | null>(null)

const ALLOWED_TYPES = [
  'application/vnd.android.package-archive',
  'application/octet-stream',
]
const ALLOWED_EXTENSIONS = ['.apk', '.aab']

function isValidFile(file: File): boolean {
  const extension = '.' + file.name.split('.').pop()?.toLowerCase()
  return ALLOWED_EXTENSIONS.includes(extension)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function openFilePicker() {
  fileInput.value?.click()
}

function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) selectFile(file)
}

function onDrop(event: DragEvent) {
  isDragOver.value = false
  const file = event.dataTransfer?.files[0]
  if (file) selectFile(file)
}

function selectFile(file: File) {
  if (!isValidFile(file)) {
    error.value = 'Please select an APK or AAB file'
    return
  }
  selectedFile.value = file
  error.value = null
}

function clearFile() {
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function upload() {
  if (!selectedFile.value) return

  isUploading.value = true
  error.value = null

  try {
    await appsStore.uploadApp(
      selectedFile.value,
      appName.value || undefined,
      description.value || undefined
    )
    emit('uploaded')
    close()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Upload failed'
  } finally {
    isUploading.value = false
  }
}

function close() {
  model.value = false
}

function reset() {
  selectedFile.value = null
  appName.value = ''
  description.value = ''
  error.value = null
  isDragOver.value = false
  if (fileInput.value) fileInput.value.value = ''
}

// Reset when dialog closes
watch(model, (open) => {
  if (!open) reset()
})
</script>

<style scoped>
.drop-zone {
  border: 2px dashed rgba(var(--v-border-color), var(--v-border-opacity));
  cursor: pointer;
  transition: all 0.2s;
}

.drop-zone:hover,
.drop-zone--active {
  border-color: rgb(var(--v-theme-primary));
  background-color: rgba(var(--v-theme-primary), 0.05);
}

.drop-zone--has-file {
  border-color: rgb(var(--v-theme-success));
  border-style: solid;
}
</style>
```

### Acceptance Criteria

- [ ] Drag-and-drop file selection works
- [ ] Click to browse works
- [ ] Only APK/AAB files accepted
- [ ] Invalid file types show error
- [ ] Optional name/description fields work
- [ ] Upload progress shown
- [ ] Success closes dialog and emits event
- [ ] Error displayed in dialog
- [ ] Cancel closes dialog
- [ ] Dialog resets when closed

---

## Task 5: Edit App Dialog

**Status**: done

### Description

Create a dialog for editing app metadata (name and description). The dialog:
- Pre-populates with current values
- Validates that name is not empty
- Shows loading state during save
- Handles errors gracefully

### Implementation

Create `src/components/EditAppDialog.vue`:

```vue
<template>
  <v-dialog v-model="model" max-width="500">
    <v-card>
      <v-card-title>Edit Application</v-card-title>

      <v-card-text>
        <v-form ref="form" v-model="isValid">
          <v-text-field
            v-model="name"
            label="App Name"
            :rules="[rules.required]"
            class="mb-4"
          />

          <v-textarea
            v-model="description"
            label="Description"
            rows="3"
            hint="Optional description for the app"
            persistent-hint
          />
        </v-form>

        <v-alert v-if="error" type="error" variant="tonal" class="mt-4">
          {{ error }}
        </v-alert>
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isSaving" @click="close">
          Cancel
        </v-btn>
        <v-btn
          color="primary"
          :disabled="!isValid || !hasChanges"
          :loading="isSaving"
          @click="save"
        >
          Save
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useAppsStore } from '@/stores/apps'
import type { App } from '@/services/api'

const props = defineProps<{
  app: App | null
}>()

const model = defineModel<boolean>()
const emit = defineEmits<{
  saved: [void]
}>()

const appsStore = useAppsStore()

const form = ref()
const name = ref('')
const description = ref('')
const isValid = ref(false)
const isSaving = ref(false)
const error = ref<string | null>(null)

const rules = {
  required: (v: string) => !!v?.trim() || 'Name is required',
}

const hasChanges = computed(() => {
  if (!props.app) return false
  return name.value !== props.app.name ||
         description.value !== (props.app.description || '')
})

async function save() {
  if (!props.app || !isValid.value) return

  isSaving.value = true
  error.value = null

  try {
    await appsStore.updateApp(props.app.packageName, {
      name: name.value,
      description: description.value || undefined,
    })
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to save'
  } finally {
    isSaving.value = false
  }
}

function close() {
  model.value = false
}

function reset() {
  if (props.app) {
    name.value = props.app.name
    description.value = props.app.description || ''
  }
  error.value = null
}

// Reset when dialog opens or app changes
watch([model, () => props.app], ([open]) => {
  if (open) reset()
})
</script>
```

### Acceptance Criteria

- [ ] Dialog pre-populates with current app values
- [ ] Name field is required
- [ ] Save button disabled when no changes
- [ ] Loading state shown during save
- [ ] Success closes dialog and emits event
- [ ] Error displayed in dialog
- [ ] Cancel closes dialog without saving

---

## Task 6: Delete Confirmation Dialogs

**Status**: done

### Description

Create a reusable confirmation dialog component for destructive actions. Used for:
- Deleting an entire app
- Deleting a specific version
- Any other destructive operation

### Implementation

Create `src/components/ConfirmDialog.vue`:

```vue
<template>
  <v-dialog v-model="model" max-width="400">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon :color="confirmColor" class="mr-2">mdi-alert-circle</v-icon>
        {{ title }}
      </v-card-title>

      <v-card-text>
        {{ message }}
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isLoading" @click="cancel">
          {{ cancelText }}
        </v-btn>
        <v-btn
          :color="confirmColor"
          :loading="isLoading"
          @click="confirm"
        >
          {{ confirmText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  confirmColor?: string
}>(), {
  confirmText: 'Confirm',
  cancelText: 'Cancel',
  confirmColor: 'primary',
})

const model = defineModel<boolean>()
const emit = defineEmits<{
  confirm: [void]
  cancel: [void]
}>()

const isLoading = ref(false)

async function confirm() {
  isLoading.value = true
  try {
    emit('confirm')
  } finally {
    isLoading.value = false
    model.value = false
  }
}

function cancel() {
  emit('cancel')
  model.value = false
}
</script>
```

### Key Features

- **Reusable**: Works for any confirmation scenario
- **Customizable**: Title, message, button text, button color
- **Loading state**: Shows loading during async confirm action
- **Accessible**: Clear visual warning with icon

### Acceptance Criteria

- [ ] Dialog displays title and message
- [ ] Confirm and cancel buttons work
- [ ] Button text and colors customizable
- [ ] Loading state during confirm
- [ ] Dialog closes after confirm/cancel

---

## Task 7: Toast Notifications

**Status**: done

### Description

Implement a toast notification system for user feedback. Toasts show:
- Success messages (green) for completed actions
- Error messages (red) for failures
- Info messages (blue) for general information

Use Vuetify's snackbar component with a composable for easy use across the app.

### Implementation

Create `src/composables/useToast.ts`:

```typescript
import { ref, readonly } from 'vue'

interface Toast {
  id: number
  message: string
  type: 'success' | 'error' | 'info' | 'warning'
  timeout: number
}

const toasts = ref<Toast[]>([])
let nextId = 0

function addToast(message: string, type: Toast['type'], timeout = 4000) {
  const id = nextId++
  toasts.value.push({ id, message, type, timeout })

  // Auto-remove after timeout
  setTimeout(() => {
    removeToast(id)
  }, timeout)
}

function removeToast(id: number) {
  const index = toasts.value.findIndex(t => t.id === id)
  if (index >= 0) {
    toasts.value.splice(index, 1)
  }
}

export function useToast() {
  return {
    toasts: readonly(toasts),
    success: (message: string) => addToast(message, 'success'),
    error: (message: string) => addToast(message, 'error'),
    info: (message: string) => addToast(message, 'info'),
    warning: (message: string) => addToast(message, 'warning'),
    remove: removeToast,
  }
}
```

Create `src/components/ToastContainer.vue`:

```vue
<template>
  <div class="toast-container">
    <v-snackbar
      v-for="toast in toasts"
      :key="toast.id"
      :model-value="true"
      :color="toast.type"
      :timeout="toast.timeout"
      location="bottom right"
      @update:model-value="remove(toast.id)"
    >
      {{ toast.message }}
      <template #actions>
        <v-btn variant="text" @click="remove(toast.id)">
          Close
        </v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts, remove } = useToast()
</script>
```

Add to `App.vue`:

```vue
<template>
  <router-view />
  <ToastContainer />
</template>

<script setup lang="ts">
import ToastContainer from '@/components/ToastContainer.vue'
</script>
```

### Usage Example

```typescript
import { useToast } from '@/composables/useToast'

const toast = useToast()

// In your component
toast.success('App uploaded successfully')
toast.error('Failed to delete version')
toast.info('Processing upload...')
```

### Acceptance Criteria

- [ ] Toast composable created
- [ ] ToastContainer component created
- [ ] Success, error, info, warning types work
- [ ] Toasts auto-dismiss after timeout
- [ ] Multiple toasts can stack
- [ ] Close button dismisses toast
- [ ] Integrated into App.vue

---

## Task 8: Loading States & Skeletons

**Status**: done

### Description

Ensure all async operations show appropriate loading states. This includes:
- Skeleton loaders for initial page loads
- Button loading states for actions
- Progress indicators for uploads
- Disabled states during operations

### Implementation Details

1. **List view loading**: Use `v-skeleton-loader type="table"` while fetching apps
2. **Detail view loading**: Use `v-skeleton-loader type="article, table"`
3. **Button loading**: Use `:loading` prop on action buttons
4. **Upload progress**: Use `v-progress-linear indeterminate` during upload
5. **Disable interactions**: Use `:disabled` to prevent double-clicks

### Example Skeleton Usage

```vue
<template>
  <!-- Loading state -->
  <v-skeleton-loader v-if="isLoading" type="table" />

  <!-- Content -->
  <v-data-table v-else :items="items" />
</template>
```

### Acceptance Criteria

- [ ] List view shows skeleton while loading
- [ ] Detail view shows skeleton while loading
- [ ] Action buttons show loading spinner
- [ ] Upload shows progress indicator
- [ ] Buttons disabled during async operations
- [ ] No layout shift when loading completes

---

## Task 9: Error Handling

**Status**: done

### Description

Implement comprehensive error handling across the frontend:
- API errors displayed to user
- Network errors handled gracefully
- 404 states for missing resources
- Retry options where appropriate

### Error Scenarios

1. **Network error**: "Unable to connect to server. Please check your connection."
2. **401 Unauthorized**: Redirect to login (already handled in API client)
3. **403 Forbidden**: "You don't have permission to perform this action."
4. **404 Not Found**: Show not found state in UI
5. **409 Conflict**: "Version already exists. Delete it first to re-upload."
6. **413 Payload Too Large**: "File is too large. Maximum size is 500MB."
7. **500 Server Error**: "An unexpected error occurred. Please try again."

### Implementation

Update API client to provide better error messages:

```typescript
// In api.ts request function
if (!response.ok) {
  let error = { error: 'unknown', message: 'An error occurred' }
  try {
    error = await response.json()
  } catch {
    // Map status codes to friendly messages
    switch (response.status) {
      case 403:
        error.message = "You don't have permission to perform this action"
        break
      case 413:
        error.message = 'File is too large. Maximum size is 500MB'
        break
      case 500:
        error.message = 'An unexpected server error occurred'
        break
    }
  }
  throw new ApiError(response.status, error.error, error.message)
}
```

### Acceptance Criteria

- [ ] API errors show user-friendly messages
- [ ] Network errors handled with retry option
- [ ] 404 states shown appropriately
- [ ] Error toasts use consistent messaging
- [ ] No unhandled promise rejections in console

---

## Task 10: Router Updates

**Status**: done

### Description

Update the Vue Router configuration to add routes for the apps list and detail views. The routes should:
- Be protected (require authentication)
- Use lazy loading for code splitting
- Handle URL parameters correctly

### Implementation

Update `src/router/index.ts`:

```typescript
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: { name: 'apps' },
  },
  {
    path: '/apps',
    name: 'apps',
    component: () => import('@/views/AppsListView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/apps/:packageName',
    name: 'app-detail',
    component: () => import('@/views/AppDetailView.vue'),
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
```

### Acceptance Criteria

- [ ] `/apps` route shows apps list
- [ ] `/apps/:packageName` route shows app detail
- [ ] `/` redirects to `/apps`
- [ ] All routes are protected
- [ ] Routes use lazy loading

---

## Task 11: Dashboard Integration

**Status**: done

### Description

Update the existing DashboardView to redirect to the apps list, or convert it to be the apps list. Also update the navigation drawer to include proper links.

### Implementation Options

**Option A**: Redirect dashboard to apps list
- Keep dashboard as a placeholder
- Root path `/` redirects to `/apps`

**Option B**: Replace dashboard with apps list
- Remove separate dashboard
- Apps list becomes the main view

### Recommended: Option A

This maintains flexibility for future dashboard features while making apps list the default view.

### Update Navigation

Update `DefaultLayout.vue` navigation:

```vue
<v-list nav>
  <v-list-item
    prepend-icon="mdi-package-variant"
    title="Applications"
    :to="{ name: 'apps' }"
  />
</v-list>
```

### Acceptance Criteria

- [ ] Root path shows apps list
- [ ] Navigation drawer links work
- [ ] Active route highlighted in nav

---

## Task 12: Component Tests

**Status**: un-done

### Description

Add unit tests for the key components using Vitest and Vue Test Utils. Focus on:
- Component rendering
- User interactions
- State changes
- API calls (mocked)

### Setup

Add test dependencies:

```bash
npm install -D vitest @vue/test-utils @testing-library/vue jsdom
```

Update `vite.config.ts`:

```typescript
export default defineConfig({
  // ... existing config
  test: {
    globals: true,
    environment: 'jsdom',
  },
})
```

### Test Files to Create

1. `src/stores/__tests__/apps.test.ts` - Apps store tests
2. `src/components/__tests__/UploadDialog.test.ts` - Upload dialog tests
3. `src/components/__tests__/ConfirmDialog.test.ts` - Confirm dialog tests

### Example Test

```typescript
// src/stores/__tests__/apps.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppsStore } from '../apps'
import { api } from '@/services/api'

vi.mock('@/services/api')

describe('Apps Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('fetches apps from API', async () => {
    const mockApps = [{ packageName: 'com.test.app', name: 'Test App' }]
    vi.mocked(api.getApps).mockResolvedValue({ apps: mockApps })

    const store = useAppsStore()
    await store.fetchApps()

    expect(store.apps).toEqual(mockApps)
    expect(store.isLoading).toBe(false)
  })

  it('handles fetch error', async () => {
    vi.mocked(api.getApps).mockRejectedValue(new Error('Network error'))

    const store = useAppsStore()
    await expect(store.fetchApps()).rejects.toThrow()

    expect(store.error).toBe('Network error')
  })
})
```

### Acceptance Criteria

- [ ] Test framework configured
- [ ] Apps store tests passing
- [ ] Upload dialog tests passing
- [ ] Confirm dialog tests passing
- [ ] Tests run in CI/pre-commit

---

## Files to Create

```
frontend/src/
├── stores/
│   └── apps.ts                    # Task 1
├── views/
│   ├── AppsListView.vue           # Task 2
│   └── AppDetailView.vue          # Task 3
├── components/
│   ├── UploadDialog.vue           # Task 4
│   ├── EditAppDialog.vue          # Task 5
│   ├── ConfirmDialog.vue          # Task 6
│   └── ToastContainer.vue         # Task 7
├── composables/
│   └── useToast.ts                # Task 7
└── stores/__tests__/
    └── apps.test.ts               # Task 12
```

---

## Verification Checklist

When all tasks are complete, verify the following:

### App Management
- [ ] Apps list shows all uploaded apps
- [ ] Search filters apps correctly
- [ ] Click app navigates to detail view
- [ ] App detail shows all versions
- [ ] Version download links work

### CRUD Operations
- [ ] Upload APK creates new app
- [ ] Upload APK to existing app adds version
- [ ] Edit app updates name/description
- [ ] Delete version removes just that version
- [ ] Delete app removes app and all versions

### User Experience
- [ ] Loading states shown during fetches
- [ ] Success toasts for completed actions
- [ ] Error toasts for failures
- [ ] Confirmation dialogs for destructive actions
- [ ] Empty states when no data

### Error Handling
- [ ] Network errors show friendly message
- [ ] 404 errors show not found state
- [ ] Upload validation errors displayed
- [ ] API errors shown in toasts
