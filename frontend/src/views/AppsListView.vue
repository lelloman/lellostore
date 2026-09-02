<template>
  <DefaultLayout>
    <header class="catalog-header">
      <div>
        <p class="page-kicker mb-3">Private distribution</p>
        <h1 class="page-title mb-3">App catalog</h1>
        <p class="catalog-intro text-medium-emphasis">
          Browse the Android apps available to your organization and stay current
          with every release.
        </p>
      </div>

      <div class="catalog-actions">
        <v-btn
          variant="outlined"
          prepend-icon="mdi-refresh"
          :loading="appsStore.isLoading"
          @click="refreshApps"
        >
          Refresh
        </v-btn>
        <v-btn
          v-if="authStore.isAdmin"
          color="primary"
          prepend-icon="mdi-cloud-upload-outline"
          @click="showUploadDialog = true"
        >
          Upload app
        </v-btn>
      </div>
    </header>

    <v-alert
      v-if="appsStore.error && !appsStore.isLoading"
      class="mb-6"
      type="error"
      variant="tonal"
      closable
      @click:close="appsStore.clearError"
    >
      {{ appsStore.error }}
    </v-alert>

    <v-card class="catalog-toolbar surface-panel mb-7">
      <v-text-field
        v-model="search"
        class="catalog-search"
        prepend-inner-icon="mdi-magnify"
        placeholder="Search by app name or package"
        aria-label="Search applications"
        variant="solo-filled"
        flat
        hide-details
        clearable
      />
      <div class="catalog-count">
        <span class="count-value">{{ appsStore.appCount }}</span>
        <span class="text-medium-emphasis">
          {{ appsStore.appCount === 1 ? 'application' : 'applications' }}
        </span>
      </div>
    </v-card>

    <v-row v-if="appsStore.isLoading" class="app-grid">
      <v-col v-for="index in 6" :key="index" cols="12" sm="6" lg="4">
        <v-skeleton-loader class="app-skeleton" type="avatar, heading, paragraph, actions" />
      </v-col>
    </v-row>

    <v-card
      v-else-if="appsStore.apps.length === 0"
      class="empty-state surface-panel text-center"
    >
      <span class="empty-icon mb-5">
        <v-icon size="42">mdi-package-variant-plus</v-icon>
      </span>
      <h2 class="text-h5 font-weight-bold mb-2">Your catalog is ready</h2>
      <p class="text-body-1 text-medium-emphasis mb-6">
        {{ authStore.isAdmin
          ? 'Upload an APK or AAB to publish the first application.'
          : 'There are no applications available yet.' }}
      </p>
      <v-btn
        v-if="authStore.isAdmin"
        color="primary"
        prepend-icon="mdi-cloud-upload-outline"
        @click="showUploadDialog = true"
      >
        Upload first app
      </v-btn>
    </v-card>

    <v-card
      v-else-if="filteredApps.length === 0"
      class="empty-state surface-panel text-center"
    >
      <span class="empty-icon mb-5">
        <v-icon size="42">mdi-magnify-close</v-icon>
      </span>
      <h2 class="text-h5 font-weight-bold mb-2">No matching apps</h2>
      <p class="text-body-1 text-medium-emphasis mb-6">
        Try a different name or package identifier.
      </p>
      <v-btn variant="outlined" @click="search = ''">Clear search</v-btn>
    </v-card>

    <v-row v-else class="app-grid">
      <v-col
        v-for="item in filteredApps"
        :key="item.package_name"
        cols="12"
        sm="6"
        lg="4"
      >
        <v-card
          class="app-card surface-panel h-100"
          role="link"
          tabindex="0"
          :aria-label="`Open ${item.name}`"
          @click="openApp(item.package_name)"
          @keydown.enter="openApp(item.package_name)"
          @keydown.space.prevent="openApp(item.package_name)"
        >
          <div class="app-card-top">
            <div class="app-icon">
              <AuthenticatedImg :src="getIconUrl(item.package_name)" cover>
                <template #fallback>
                  <v-icon icon="mdi-android" color="primary" size="32" />
                </template>
              </AuthenticatedImg>
            </div>
            <v-chip
              v-if="item.latest_version"
              color="primary"
              size="small"
              variant="tonal"
            >
              v{{ item.latest_version.version_name }}
            </v-chip>
          </div>

          <div class="app-card-body">
            <h2 class="app-name">{{ item.name }}</h2>
            <p class="app-package">{{ item.package_name }}</p>
            <p class="app-description text-medium-emphasis">
              {{ item.description || 'No description provided.' }}
            </p>
          </div>

          <div class="app-card-footer">
            <div v-if="item.latest_version" class="release-meta">
              <span>{{ formatSize(item.latest_version.size) }}</span>
              <span aria-hidden="true">·</span>
              <span>API {{ item.latest_version.min_sdk }}+</span>
              <span aria-hidden="true">·</span>
              <span>{{ formatDate(item.latest_version.uploaded_at) }}</span>
            </div>
            <span v-else class="text-medium-emphasis">No releases</span>
            <v-icon color="primary" size="20">mdi-arrow-right</v-icon>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <UploadDialog
      v-if="authStore.isAdmin"
      v-model="showUploadDialog"
      @uploaded="onUploaded"
    />
  </DefaultLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import AuthenticatedImg from '@/components/AuthenticatedImg.vue'
import { useAppsStore } from '@/stores/apps'
import { useAuthStore } from '@/stores/auth'
import { api } from '@/services/api'

const router = useRouter()
const appsStore = useAppsStore()
const authStore = useAuthStore()

const search = ref('')
const showUploadDialog = ref(false)

const filteredApps = computed(() => {
  const query = search.value?.trim().toLocaleLowerCase() ?? ''
  if (!query) return appsStore.sortedApps
  return appsStore.sortedApps.filter(app =>
    [app.name, app.package_name, app.description]
      .filter(Boolean)
      .some(value => value!.toLocaleLowerCase().includes(query))
  )
})

function getIconUrl(packageName: string) {
  return api.getIconUrl(packageName)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(date?: string): string {
  if (!date) return 'Unknown date'
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(date))
}

function openApp(packageName: string) {
  router.push({ name: 'app-detail', params: { packageName } })
}

async function refreshApps() {
  try {
    await appsStore.fetchApps()
  } catch {
    // The store exposes the error in the page alert.
  }
}

function onUploaded() {
  showUploadDialog.value = false
}

onMounted(refreshApps)
</script>

<style scoped>
.catalog-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 2rem;
  margin-bottom: 2.25rem;
}

.catalog-intro {
  max-width: 660px;
  font-size: 1.05rem;
  line-height: 1.65;
}

.catalog-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 0.75rem;
}

.catalog-toolbar {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding: 0.75rem;
  background: rgba(var(--v-theme-surface), 0.9);
}

.catalog-search {
  max-width: 620px;
}

.catalog-count {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  padding-right: 1rem;
  white-space: nowrap;
}

.count-value {
  font-size: 1.15rem;
  font-weight: 800;
}

.app-grid {
  margin: -0.65rem;
}

.app-grid > :deep(.v-col) {
  padding: 0.65rem;
}

.app-card,
.app-skeleton {
  min-height: 300px;
}

.app-card {
  display: flex;
  flex-direction: column;
  padding: 1.4rem;
  background: rgba(var(--v-theme-surface), 0.92);
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.app-card:hover,
.app-card:focus-visible {
  border-color: rgba(var(--v-theme-primary), 0.32);
  box-shadow: 0 24px 54px rgba(20, 52, 33, 0.13) !important;
  transform: translateY(-4px);
  outline: none;
}

.app-card-top,
.app-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.app-icon {
  display: grid;
  width: 66px;
  height: 66px;
  overflow: hidden;
  place-items: center;
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 18px;
  background: rgb(var(--v-theme-surface-variant));
}

.app-card-body {
  flex: 1;
  padding-block: 1.35rem;
}

.app-name {
  overflow: hidden;
  margin-bottom: 0.2rem;
  font-size: 1.25rem;
  font-weight: 760;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-package {
  overflow: hidden;
  margin-bottom: 1rem;
  color: rgb(var(--v-theme-primary));
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.78rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-description {
  display: -webkit-box;
  overflow: hidden;
  min-height: 3rem;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.app-card-footer {
  padding-top: 1rem;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.08);
  font-size: 0.78rem;
}

.release-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.4rem;
  color: rgb(var(--v-theme-secondary));
}

.empty-state {
  display: flex;
  min-height: 360px;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  padding: 3rem 1.5rem;
}

.empty-icon {
  display: grid;
  width: 88px;
  height: 88px;
  place-items: center;
  border-radius: 28px;
  color: rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.1);
}

@media (max-width: 700px) {
  .catalog-header {
    align-items: stretch;
    flex-direction: column;
  }

  .catalog-actions > :deep(.v-btn) {
    flex: 1;
  }

  .catalog-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .catalog-search {
    max-width: none;
  }

  .catalog-count {
    padding: 0 0.5rem 0.35rem;
  }

  .release-meta span:last-child,
  .release-meta span:nth-last-child(2) {
    display: none;
  }
}
</style>
