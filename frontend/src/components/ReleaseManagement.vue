<template>
  <v-card class="surface-panel">
    <div class="d-flex align-center justify-space-between flex-wrap ga-3 pa-5">
      <div>
        <p class="text-overline">Release management</p>
        <h2 class="text-h5">{{ app.distribution_mode === 'paravoid' ? 'Paravoid distribution' : 'Normal distribution' }}</h2>
      </div>
      <v-btn color="primary" prepend-icon="mdi-cloud-upload-outline" @click="$emit('upload')">Upload draft</v-btn>
    </div>
    <v-tabs v-model="tab" color="primary">
      <v-tab value="releases">Releases</v-tab>
      <v-tab value="installers">Installers</v-tab>
      <v-tab value="history">Publication history</v-tab>
      <v-tab value="access">Access</v-tab>
      <v-tab value="paravoid">Paravoid</v-tab>
    </v-tabs>
    <v-divider />
    <v-alert v-if="error" type="error" variant="tonal" class="ma-4" role="alert">{{ error }}</v-alert>
    <v-window v-model="tab">
      <v-window-item value="releases">
        <p class="pa-4 text-body-2 text-medium-emphasis">Drafts are visible only to administrators. Publishing makes a release available; withdrawing stops new downloads without uninstalling existing copies.</p>
        <v-data-table :headers="headers" :items="versions" :items-per-page="10" item-value="version_code">
          <template #item.version_name="{ item }">
            <strong>{{ item.version_name }}</strong>
            <div class="text-caption">APK {{ item.version_code }} · {{ item.is_beta ? 'Beta' : 'Stable' }}</div>
          </template>
          <template #item.publication_state="{ item }">
            <v-chip :color="state(item) === 'published' ? 'success' : state(item) === 'draft' ? 'primary' : undefined" size="small">{{ state(item) }}</v-chip>
          </template>
          <template #item.actions="{ item }">
            <v-btn v-if="state(item) === 'draft'" variant="text" :disabled="busy" @click="review(item)">Review draft</v-btn>
            <v-btn v-else-if="state(item) === 'published'" variant="text" color="warning" :disabled="busy" @click="review(item)">Withdraw</v-btn>
            <span v-else class="text-caption text-medium-emphasis">Retained in history</span>
          </template>
          <template #no-data><div class="pa-6">No releases yet. Upload an APK to prepare a draft.</div></template>
        </v-data-table>
      </v-window-item>
      <v-window-item value="installers">
        <div class="pa-5">
          <p class="mb-4">Normal releases contain the full app. Installer version codes increase across stable and beta, including withdrawn releases.</p>
          <v-list>
            <v-list-item v-for="version in versions" :key="version.version_code" :title="`${version.version_name} · APK ${version.version_code}`" :subtitle="`${state(version)} · Android API ${version.min_sdk}+ · ${formatSize(version.size)}`">
              <template #append><v-chip size="small">{{ version.distribution_mode ?? 'normal' }}</v-chip></template>
            </v-list-item>
          </v-list>
        </div>
      </v-window-item>
      <v-window-item value="history">
        <div class="pa-5">
          <v-btn variant="text" :loading="historyLoading" @click="loadHistory">Refresh history</v-btn>
          <v-progress-linear v-if="historyLoading" indeterminate />
          <v-list v-else-if="history.length">
            <v-list-item v-for="event in history" :key="event.id" :title="`${event.action === 'publish' ? 'Published' : 'Withdrew'} APK ${event.version_code}`" :subtitle="`${event.actor_subject} · ${event.created_at} · revision ${event.revision}`" />
          </v-list>
          <p v-else class="mt-4 text-medium-emphasis">No publication actions recorded yet. Existing releases were preserved during migration.</p>
        </div>
      </v-window-item>
      <v-window-item value="access">
        <div class="pa-5">
          <p class="mb-4">Manage the users and groups that can acquire this application. Group rules are evaluated live.</p>
          <v-btn :to="{ name: 'access-admin' }" variant="outlined">Manage app access</v-btn>
        </div>
      </v-window-item>
    <v-window-item value="paravoid"><ParavoidManagement v-if="tab === 'paravoid'" :package-name="app.package_name" @changed="emit('changed')" /></v-window-item>
    </v-window>
  </v-card>

  <v-dialog :model-value="selected !== null" max-width="680" :persistent="busy" @update:model-value="value => { if (!value && !busy) selected = null }">
    <v-card v-if="selected">
      <v-card-title>{{ state(selected) === 'draft' ? 'Review release' : 'Withdraw release' }}</v-card-title>
      <v-card-text>
        <v-alert v-if="dialogError" type="error" variant="tonal" class="mb-4" role="alert">{{ dialogError }}</v-alert>
        <h3>{{ selected.version_name }} · APK {{ selected.version_code }}</h3>
        <p class="text-body-2 mb-4">{{ app.package_name }} · {{ formatSize(selected.size) }} · Android API {{ selected.min_sdk }}+</p>
        <template v-if="state(selected) === 'draft'">
          <v-select v-model="isBeta" label="Release channel" :items="[{ title: 'Stable', value: false }, { title: 'Beta', value: true }]" :disabled="busy" />
          <v-textarea v-model="notes" label="Release notes" rows="4" :disabled="busy" counter="65536" />
          <v-alert type="info" variant="tonal" class="mb-3">The APK was parsed and checksummed. Publication rechecks its stored bytes and version ordering. App behavior must be tested before publishing.</v-alert>
          <v-checkbox v-model="replaceLatest" label="Withdraw the previous latest release in this channel" :disabled="busy" hide-details />
          <p class="text-caption mb-3">Its artifact and published identity remain retained.</p>
          <details><summary>Artifact verification details</summary><p class="hash mt-2">SHA-256: {{ selected.sha256 }}</p></details>
          <p v-if="dirty" class="text-body-2 mt-3">Save your changes before publishing.</p>
        </template>
        <v-alert v-else type="warning" variant="tonal">This stops offering the release for new installs and updates. Installed copies keep working. The published identity and artifact remain retained.</v-alert>
      </v-card-text>
      <v-card-actions>
        <v-btn :disabled="busy" @click="selected = null">Close</v-btn>
        <v-spacer />
        <template v-if="state(selected) === 'draft'">
          <v-btn :disabled="busy || !dirty" @click="save">Save draft</v-btn>
          <v-btn color="primary" :loading="busy" :disabled="dirty || busy" @click="publish">Publish release</v-btn>
        </template>
        <v-btn v-else color="warning" :loading="busy" :disabled="busy" @click="withdraw">Withdraw release</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ParavoidManagement from './ParavoidManagement.vue'
import { api, type App, type AppVersion, type PublicationEvent } from '@/services/api'

const props = defineProps<{ app: App }>()
const emit = defineEmits<{ changed: []; upload: [] }>()
const tab = ref('releases')
const selected = ref<AppVersion | null>(null)
const revision = ref(0)
const notes = ref('')
const isBeta = ref(false)
const replaceLatest = ref(false)
const busy = ref(false)
const error = ref('')
const dialogError = ref('')
const history = ref<PublicationEvent[]>([])
const historyLoading = ref(false)
const versions = computed(() => [...props.app.versions].sort((a, b) => b.version_code - a.version_code))
const dirty = computed(() => selected.value && (notes.value !== (selected.value.release_notes ?? '') || isBeta.value !== !!selected.value.is_beta))
const headers = [{ title: 'Release', key: 'version_name' }, { title: 'Status', key: 'publication_state' }, { title: 'Actions', key: 'actions', sortable: false }]
const state = (version: AppVersion) => version.publication_state ?? 'published'
const message = (cause: unknown) => cause instanceof Error ? cause.message : 'The action failed. Please retry.'
const formatSize = (size: number) => `${(size / 1024 / 1024).toFixed(1)} MB`

async function review(version: AppVersion) {
  busy.value = true
  error.value = ''
  try {
    const fresh = await api.getAdminApp(props.app.package_name)
    const release = fresh.versions.find(v => v.version_code === version.version_code)
    if (!release || state(release) !== state(version)) throw new Error('This release changed. Refresh the app before reviewing it.')
    selected.value = release
    revision.value = fresh.publication_revision ?? 0
    notes.value = release.release_notes ?? ''
    isBeta.value = !!release.is_beta
    replaceLatest.value = false
    dialogError.value = ''
  } catch (cause) { error.value = message(cause) }
  finally { busy.value = false }
}

async function save() {
  if (!selected.value) return
  busy.value = true
  dialogError.value = ''
  try {
    await api.saveDraft(props.app.package_name, selected.value.version_code, notes.value, isBeta.value)
    const fresh = await api.getAdminApp(props.app.package_name)
    selected.value = fresh.versions.find(v => v.version_code === selected.value?.version_code) ?? null
    revision.value = fresh.publication_revision ?? 0
    emit('changed')
  } catch (cause) { dialogError.value = message(cause) }
  finally { busy.value = false }
}

async function publish() {
  if (!selected.value || dirty.value || busy.value) return
  await mutate(() => api.publishRelease(props.app.package_name, selected.value!.version_code, revision.value, replaceLatest.value))
}
async function withdraw() {
  if (!selected.value || busy.value) return
  await mutate(() => api.withdrawRelease(props.app.package_name, selected.value!.version_code, revision.value))
}
async function mutate(action: () => Promise<unknown>) {
  busy.value = true
  dialogError.value = ''
  try {
    await action()
    selected.value = null
    emit('changed')
    await loadHistory()
  } catch (cause) { dialogError.value = message(cause) }
  finally { busy.value = false }
}
async function loadHistory() {
  historyLoading.value = true
  error.value = ''
  try { history.value = await api.getPublicationHistory(props.app.package_name) }
  catch (cause) { error.value = message(cause) }
  finally { historyLoading.value = false }
}
watch(() => props.app.package_name, () => { selected.value = null; history.value = []; error.value = '' })
watch(tab, value => { if (value === 'history') void loadHistory() })
</script>

<style scoped>
.hash { overflow-wrap: anywhere; font-family: monospace; font-size: 0.8rem; }
</style>
