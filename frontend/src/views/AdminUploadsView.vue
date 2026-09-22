<template>
  <v-container>
    <div class="d-flex align-center mb-4">
      <h1>Uploads</h1>
      <v-spacer />
      <v-btn :loading="loading" @click="refresh">Refresh</v-btn>
    </div>
    <p class="mb-4">Validation continues when you leave this page. Ready uploads are drafts awaiting review and publication. Showing the latest 100 uploads.</p>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="!jobs.length && !loading" type="info">No uploads yet.</v-alert>
    <v-card v-for="job in jobs" :key="job.id" class="mb-3" :title="job.file_name" :subtitle="`${job.created_at} · ${job.status}`">
      <v-card-text>
        <p>Upload {{ job.id }}</p>
        <p>Submitted by {{ job.actor_subject }}</p>
        <p>{{ job.kind === 'vpk' ? 'VPK payload' : job.distribution_mode === 'paravoid' ? 'Paravoid shell APK' : 'Normal APK' }}</p>
        <v-alert v-if="job.error" type="error" class="mt-3">{{ job.error }}</v-alert>
      </v-card-text>
      <v-card-actions>
        <v-btn v-if="job.status === 'ready' && packageFor(job)" :to="{ name: 'app-detail', params: { packageName: packageFor(job) } }">Review draft</v-btn>
        <v-btn v-if="job.status === 'failed'" :loading="retrying === job.id" @click="retry(job.id)">Retry validation</v-btn>
        <span v-if="job.status === 'queued' || job.status === 'validating'" class="pa-2">Refresh to check progress</span>
      </v-card-actions>
    </v-card>
  </v-container>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type UploadJob } from '@/services/api'
const jobs = ref<UploadJob[]>([])
const loading = ref(false)
const retrying = ref<string | null>(null)
const error = ref('')
function packageFor(job: UploadJob): string | undefined {
  try { return JSON.parse(job.result_json || '{}').package_name } catch { return undefined }
}
async function refresh() {
  loading.value = true
  error.value = ''
  try { jobs.value = await api.getUploads() }
  catch (e) { error.value = e instanceof Error ? e.message : 'Could not load uploads' }
  finally { loading.value = false }
}
async function retry(id: string) {
  retrying.value = id
  try { await api.retryUpload(id); await refresh() }
  catch (e) { error.value = e instanceof Error ? e.message : 'Could not retry upload' }
  finally { retrying.value = null }
}
onMounted(refresh)
</script>
