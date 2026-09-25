<template>
  <DefaultLayout>
    <div class="uploads-heading mb-7">
      <div>
        <p class="page-kicker mb-2">Administration</p>
        <h1 class="page-title">Uploads</h1>
        <p class="text-medium-emphasis mt-3 mb-0">
          Validation continues in the background. Review ready drafts to publish a release.
        </p>
      </div>
      <v-btn icon="mdi-refresh" variant="text" title="Refresh uploads" aria-label="Refresh uploads" :loading="loading" @click="refresh" />
    </div>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-6" closable @click:close="error = ''">{{ error }}</v-alert>
    <v-skeleton-loader v-if="loading && !loaded" class="surface-panel" type="table-heading, table-row-divider@4" />

    <v-card v-else-if="loaded && !jobs.length" class="empty-state surface-panel text-center">
      <span class="empty-icon mb-5"><v-icon size="42">mdi-cloud-upload-outline</v-icon></span>
      <h2 class="text-h5 font-weight-bold mb-2">No uploads yet</h2>
      <p class="text-body-1 text-medium-emphasis mb-6">Upload an app from the catalog to prepare your first draft.</p>
      <v-btn color="primary" prepend-icon="mdi-view-grid-outline" :to="{ name: 'apps' }">Go to catalog</v-btn>
    </v-card>

    <v-card v-else-if="jobs.length" class="surface-panel uploads-panel">
      <div class="panel-heading">
        <h2 class="text-subtitle-1 font-weight-bold">Recent uploads</h2>
        <span class="text-body-2 text-medium-emphasis">Showing the latest {{ jobs.length }} uploads · Up to 100</span>
      </div>
      <v-divider />
      <ul class="upload-list">
        <li v-for="job in jobs" :key="job.id" class="upload-row">
          <div class="upload-main">
            <div class="upload-icon" aria-hidden="true">
              <v-icon color="primary" :icon="job.kind === 'vpk' ? 'mdi-package-variant-closed' : 'mdi-android'" size="26" />
            </div>
            <div class="upload-info">
              <h3 class="upload-name">{{ job.file_name }}</h3>
              <p class="text-body-2 text-medium-emphasis mt-1">{{ kindLabel(job) }} · {{ formatDate(job.created_at) }}</p>
              <p class="upload-submitter text-caption text-medium-emphasis mt-1">Submitted by {{ job.actor_subject }}</p>
            </div>
            <v-chip :color="statuses[job.status].color" :prepend-icon="statuses[job.status].icon" size="small" variant="tonal">
              {{ statuses[job.status].label }}
            </v-chip>
          </div>
          <v-alert v-if="job.error" type="error" variant="tonal" class="upload-error mt-4">{{ job.error }}</v-alert>
          <div class="upload-footer">
            <span class="upload-id text-caption text-medium-emphasis">Upload {{ job.id }}</span>
            <v-btn
              v-if="job.status === 'ready' && packageFor(job)"
              color="primary" variant="tonal" size="small" append-icon="mdi-arrow-right"
              :to="{ name: 'app-detail', params: { packageName: packageFor(job) } }"
            >Review draft</v-btn>
            <v-btn
              v-if="job.status === 'failed'"
              color="primary" variant="tonal" size="small" prepend-icon="mdi-refresh"
              :loading="retrying === job.id" :disabled="retrying !== null && retrying !== job.id"
              @click="retry(job.id)"
            >Retry validation</v-btn>
            <span v-if="job.status === 'queued' || job.status === 'validating'" class="text-caption text-medium-emphasis">Refresh to check progress</span>
          </div>
        </li>
      </ul>
    </v-card>
  </DefaultLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { api, type UploadJob } from '@/services/api'

const jobs = ref<UploadJob[]>([])
const loading = ref(false)
const loaded = ref(false)
const retrying = ref<string | null>(null)
const error = ref('')
const statuses = {
  queued: { label: 'Queued', color: 'secondary', icon: 'mdi-clock-outline' },
  validating: { label: 'Validating', color: 'info', icon: 'mdi-progress-check' },
  ready: { label: 'Draft ready', color: 'success', icon: 'mdi-check-circle-outline' },
  failed: { label: 'Failed', color: 'error', icon: 'mdi-alert-circle-outline' },
}

function kindLabel(job: UploadJob): string {
  return job.kind === 'vpk' ? 'VPK payload' : job.distribution_mode === 'paravoid' ? 'Paravoid shell APK' : 'Normal APK'
}

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function packageFor(job: UploadJob): string | undefined {
  try { return JSON.parse(job.result_json || '{}').package_name } catch { return undefined }
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    jobs.value = await api.getUploads()
    loaded.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load uploads'
  } finally {
    loading.value = false
  }
}

async function retry(id: string) {
  retrying.value = id
  error.value = ''
  try { await api.retryUpload(id); await refresh() }
  catch (e) { error.value = e instanceof Error ? e.message : 'Could not retry upload' }
  finally { retrying.value = null }
}

onMounted(refresh)
</script>

<style scoped>
.uploads-heading, .panel-heading, .upload-main, .upload-footer {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.uploads-heading, .panel-heading, .upload-footer { justify-content: space-between; }
.uploads-heading > div, .upload-info { min-width: 0; }
.uploads-heading > .v-btn { flex-shrink: 0; }
.uploads-panel { overflow: hidden; }
.panel-heading { padding: 1.25rem 1.5rem; flex-wrap: wrap; }
.upload-list { list-style: none; padding: 0; }
.upload-row { padding: 1.5rem; }
.upload-row + .upload-row { border-top: 1px solid rgba(var(--v-theme-on-surface), 0.08); }
.upload-info { flex: 1; }
.upload-name { font-size: 1rem; font-weight: 700; overflow-wrap: anywhere; }
.upload-submitter, .upload-id, .upload-error { overflow-wrap: anywhere; }
.upload-icon {
  display: grid;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 14px;
  background: rgba(var(--v-theme-primary), 0.08);
}
.upload-footer { margin-top: 1rem; flex-wrap: wrap; }
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
@media (max-width: 600px) {
  .panel-heading, .upload-row { padding: 1rem; }
  .upload-main { flex-wrap: wrap; }
  .upload-info { flex-basis: calc(100% - 64px); }
}
</style>
