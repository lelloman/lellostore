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
      @click:row="onRowClick"
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
        <span v-else class="text-medium-emphasis">-</span>
      </template>

      <template #item.size="{ item }">
        {{ item.latestVersion ? formatSize(item.latestVersion.size) : '-' }}
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function onRowClick(_event: Event, row: any) {
  router.push({ name: 'app-detail', params: { packageName: row.item.packageName } })
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
