<template>
  <DefaultLayout>
    <div class="detail-page">
      <v-skeleton-loader
        v-if="appsStore.isLoading || !hasFetched"
        class="surface-panel"
        type="heading, image, article, table"
      />

      <v-alert v-else-if="!app" class="surface-panel" type="error" variant="tonal">
        <v-alert-title>Application unavailable</v-alert-title>
        The requested application could not be found.
        <template #append>
          <v-btn variant="text" @click="router.push({ name: 'apps' })">
            Back to catalog
          </v-btn>
        </template>
      </v-alert>

      <template v-else>
        <v-btn
          class="back-link mb-5"
          variant="text"
          prepend-icon="mdi-arrow-left"
          @click="router.push({ name: 'apps' })"
        >
          All applications
        </v-btn>

        <v-card class="app-hero surface-panel mb-6">
          <div class="hero-main">
            <div class="hero-icon">
              <AuthenticatedImg
                :src="api.getIconUrl(app.package_name)"
                width="100%"
                height="100%"
                cover
              >
                <template #fallback>
                  <v-icon icon="mdi-android" color="primary" size="48" />
                </template>
              </AuthenticatedImg>
            </div>

            <div class="hero-copy">
              <div class="d-flex align-center flex-wrap ga-2 mb-2">
                <p class="page-kicker mb-0">Android application</p>
                <v-chip
                  v-if="latestVersion"
                  color="primary"
                  size="small"
                  variant="tonal"
                >
                  Current · v{{ latestVersion.version_name }}
                </v-chip>
              </div>
              <h1 class="detail-title">{{ app.name }}</h1>
              <p class="detail-package">{{ app.package_name }}</p>
            </div>

            <div v-if="authStore.isAdmin" class="hero-actions">
              <v-btn variant="outlined" prepend-icon="mdi-pencil-outline" @click="showEditDialog = true">
                Edit details
              </v-btn>
              <v-btn color="error" variant="tonal" prepend-icon="mdi-delete-outline" @click="showDeleteAppDialog = true">
                Delete app
              </v-btn>
            </div>
          </div>

          <div class="release-summary">
            <div class="summary-item">
              <span class="summary-label">Latest release</span>
              <strong>{{ latestVersion?.version_name || '—' }}</strong>
            </div>
            <div class="summary-item">
              <span class="summary-label">Version code</span>
              <strong>{{ latestVersion?.version_code ?? '—' }}</strong>
            </div>
            <div class="summary-item">
              <span class="summary-label">Minimum Android</span>
              <strong>{{ latestVersion ? `API ${latestVersion.min_sdk}+` : '—' }}</strong>
            </div>
            <div class="summary-item">
              <span class="summary-label">Download size</span>
              <strong>{{ latestVersion ? formatSize(latestVersion.size) : '—' }}</strong>
            </div>
          </div>
        </v-card>

        <v-row class="detail-grid mb-2">
          <v-col cols="12" md="8">
            <v-card class="info-card surface-panel h-100">
              <p class="section-label">About this app</p>
              <p v-if="app.description" class="description-text">{{ app.description }}</p>
              <p v-else class="description-text text-medium-emphasis">
                No description has been provided for this application.
              </p>
            </v-card>
          </v-col>
          <v-col cols="12" md="4">
            <v-card class="info-card surface-panel h-100">
              <p class="section-label">Catalog information</p>
              <dl class="catalog-facts">
                <div>
                  <dt>Available releases</dt>
                  <dd>{{ app.versions.length }}</dd>
                </div>
                <div>
                  <dt>Last published</dt>
                  <dd>{{ formatDate(latestVersion?.uploaded_at) }}</dd>
                </div>
                <div>
                  <dt>Integrity</dt>
                  <dd><v-icon color="success" size="16">mdi-shield-check</v-icon> SHA-256</dd>
                </div>
              </dl>
            </v-card>
          </v-col>
        </v-row>

        <v-card class="versions-card surface-panel">
          <div class="versions-heading">
            <div>
              <p class="section-label mb-1">Release history</p>
              <h2 class="text-h5 font-weight-bold">Available versions</h2>
            </div>
            <v-btn
              v-if="authStore.isAdmin"
              color="primary"
              prepend-icon="mdi-cloud-upload-outline"
              @click="showUploadDialog = true"
            >
              Upload version
            </v-btn>
          </div>

          <v-data-table
            class="versions-table"
            :headers="versionHeaders"
            :items="sortedVersions"
            item-key="version_code"
            :items-per-page="10"
            :mobile-breakpoint="720"
          >
            <template #item.version_name="{ item }">
              <div class="version-cell">
                <strong>{{ item.version_name }}</strong>
                <v-chip
                  v-if="item.version_code === latestVersion?.version_code"
                  color="primary"
                  size="x-small"
                  variant="tonal"
                >
                  Latest
                </v-chip>
              </div>
            </template>

            <template #item.size="{ item }">
              {{ formatSize(item.size) }}
            </template>

            <template #item.min_sdk="{ item }">
              API {{ item.min_sdk }}+
            </template>

            <template #item.uploaded_at="{ item }">
              {{ formatDate(item.uploaded_at) }}
            </template>

            <template #item.actions="{ item }">
              <div class="version-actions">
                <v-btn
                  icon
                  size="small"
                  variant="text"
                  title="Download APK"
                  aria-label="Download APK"
                  @click="downloadVersion(item)"
                >
                  <v-icon>mdi-download-outline</v-icon>
                </v-btn>
                <v-btn
                  v-if="authStore.isAdmin"
                  icon
                  size="small"
                  variant="text"
                  color="error"
                  title="Delete version"
                  aria-label="Delete version"
                  @click="confirmDeleteVersion(item)"
                >
                  <v-icon>mdi-delete-outline</v-icon>
                </v-btn>
              </div>
            </template>

            <template #no-data>
              <div class="py-12 text-center text-medium-emphasis">
                No releases are available for this application.
              </div>
            </template>
          </v-data-table>
        </v-card>
      </template>
    </div>

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
import { computed, ref, watch } from 'vue'
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
const sortedVersions = computed(() =>
  [...(app.value?.versions ?? [])].sort((a, b) => b.version_code - a.version_code)
)
const latestVersion = computed(() => sortedVersions.value[0] ?? null)

const versionHeaders = [
  { title: 'Version', key: 'version_name' },
  { title: 'Code', key: 'version_code' },
  { title: 'Size', key: 'size' },
  { title: 'Compatibility', key: 'min_sdk' },
  { title: 'Published', key: 'uploaded_at' },
  { title: '', key: 'actions', sortable: false, align: 'end' as const },
]

const deleteVersionMessage = computed(() => {
  if (!versionToDelete.value || !app.value) return ''
  const isLastVersion = app.value.versions.length === 1
  if (isLastVersion) {
    return `This is the last version. Deleting it will also delete the entire app '${app.value.name}'.`
  }
  return `Are you sure you want to delete version ${versionToDelete.value.version_name}?`
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(date?: string): string {
  if (!date) return '—'
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(date))
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

watch(packageName, async name => {
  if (!name) return
  hasFetched.value = false
  try {
    await appsStore.fetchApp(name)
  } catch {
    // The store exposes the fetch error and leaves currentApp empty.
  } finally {
    hasFetched.value = true
  }
}, { immediate: true })
</script>

<style scoped>
.detail-page {
  max-width: 1180px;
  margin: 0 auto;
}

.back-link {
  margin-left: -0.75rem;
  color: rgb(var(--v-theme-secondary));
}

.app-hero {
  overflow: hidden;
  padding: clamp(1.5rem, 4vw, 2.5rem);
  background:
    linear-gradient(120deg, rgba(var(--v-theme-primary), 0.08), transparent 55%),
    rgb(var(--v-theme-surface));
}

.hero-main {
  display: flex;
  align-items: center;
  gap: 1.5rem;
}

.hero-icon {
  display: grid;
  width: 96px;
  height: 96px;
  flex: 0 0 auto;
  overflow: hidden;
  place-items: center;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 26px;
  background: rgb(var(--v-theme-surface-variant));
  box-shadow: 0 18px 34px rgba(20, 52, 33, 0.12);
}

.hero-copy {
  min-width: 0;
  flex: 1;
}

.detail-title {
  overflow: hidden;
  font-size: clamp(2rem, 4vw, 3rem);
  font-weight: 780;
  letter-spacing: -0.045em;
  line-height: 1.05;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-package {
  overflow: hidden;
  margin-top: 0.5rem;
  color: rgb(var(--v-theme-secondary));
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.88rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.release-summary {
  display: grid;
  margin-top: 2rem;
  padding-top: 1.5rem;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  grid-template-columns: repeat(4, 1fr);
}

.summary-item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.25rem;
  padding-inline: 1.25rem;
  border-right: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}

.summary-item:first-child {
  padding-left: 0;
}

.summary-item:last-child {
  border-right: 0;
}

.summary-label,
.section-label {
  color: rgb(var(--v-theme-secondary));
  font-size: 0.75rem;
  font-weight: 740;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.summary-item strong {
  overflow: hidden;
  font-size: 1.05rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-grid {
  margin-inline: -0.55rem;
}

.detail-grid > :deep(.v-col) {
  padding: 0.55rem;
}

.info-card {
  padding: 1.5rem;
}

.description-text {
  margin-top: 1rem;
  max-width: 760px;
  font-size: 1rem;
  line-height: 1.75;
  white-space: pre-wrap;
}

.catalog-facts {
  display: grid;
  gap: 0.75rem;
  margin-top: 1rem;
}

.catalog-facts div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.catalog-facts dt {
  color: rgb(var(--v-theme-secondary));
}

.catalog-facts dd {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin: 0;
  font-weight: 700;
}

.versions-card {
  margin-top: 1.1rem;
  overflow: hidden;
}

.versions-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.5rem;
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}

.versions-table :deep(th) {
  color: rgb(var(--v-theme-secondary));
  font-size: 0.72rem;
  font-weight: 750 !important;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.versions-table :deep(td) {
  height: 64px !important;
}

.version-cell,
.version-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.version-actions {
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .hero-main {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .hero-actions {
    width: 100%;
    padding-top: 0.5rem;
  }

  .release-summary {
    grid-template-columns: repeat(2, 1fr);
    row-gap: 1.25rem;
  }

  .summary-item:nth-child(2) {
    border-right: 0;
  }

  .summary-item:nth-child(3) {
    padding-left: 0;
  }
}

@media (max-width: 600px) {
  .app-hero {
    padding: 1.25rem;
  }

  .hero-icon {
    width: 76px;
    height: 76px;
    border-radius: 21px;
  }

  .hero-copy {
    width: calc(100% - 100px);
    flex: none;
  }

  .hero-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .release-summary {
    grid-template-columns: 1fr 1fr;
  }

  .summary-item {
    padding-inline: 0.75rem;
  }

  .versions-heading {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
