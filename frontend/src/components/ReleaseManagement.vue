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
          <details v-for="review in migrationHistory" :key="review.id" class="mt-4">
            <summary>Migration review: {{ review.from_mode }} → {{ review.to_mode }} · APK {{ review.from_version }} → {{ review.target_version }}</summary>
            <p>{{ review.actor_subject }} · {{ review.created_at }} · revision {{ review.review_revision }}</p>
            <p class="hash">Target SHA-256: {{ review.target_sha256 }}</p>
            <p class="hash">Signer SHA-256: {{ review.signer_sha256 }}</p>
            <pre class="hash" style="white-space: pre-wrap">{{ review.migration_evidence }}</pre>
          </details>
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
          <v-select v-model="isBeta" label="Release channel" :items="[{ title: 'Stable', value: false }, { title: 'Beta', value: true }]" :disabled="busy || selected.distribution_mode === 'paravoid'" />
          <v-textarea v-model="notes" label="Release notes" rows="4" :disabled="busy" counter="65536" />
          <v-alert type="info" variant="tonal" class="mb-3">The APK was parsed and checksummed. Publication rechecks its stored bytes and version ordering. App behavior must be tested before publishing.</v-alert>
          <template v-if="selected.distribution_mode === 'paravoid'">
            <v-alert v-if="bootstrapMode === 'embedded'" type="info" class="mb-3">This installer includes its verified bootstrap payload. The payload and installer publish together.</v-alert>
            <v-select v-model="bootstrapVpk" label="Bootstrap payload" :items="bootstrapChoices" :disabled="busy || bootstrapMode === 'embedded'" :hint="bootstrapMode === 'embedded' ? 'Verified from the signed installer; this selection cannot be changed.' : 'The selected payload and installer publish together. Upload and validate a compatible VPK in the Paravoid tab first.'" persistent-hint class="mb-4" />
          </template>
          <section v-if="changesMode" class="my-4">
            <v-alert type="warning">This stable installer changes distribution from {{ reviewedMode }} to {{ selected.distribution_mode ?? 'normal' }}. Test an in-place upgrade with real app data before publishing.</v-alert>
            <v-checkbox v-model="migration.tested_upgrade" label="I tested the in-place upgrade from the previous published installer" :disabled="busy" hide-details />
            <v-checkbox v-model="migration.database_preserved" label="Database and saved settings are preserved" :disabled="busy" hide-details />
            <v-checkbox v-model="migration.authentication_preserved" label="Existing authentication is preserved" :disabled="busy" hide-details />
            <v-checkbox v-model="migration.files_preserved" label="App files are preserved" :disabled="busy" hide-details />
            <v-textarea v-model="migration.evidence" label="Migration test evidence" hint="Record builds, devices and test results or a report link" :disabled="busy" counter="8192" />
            <v-btn :loading="busy" :disabled="busy || dirty || isBeta || !migrationReady || !!transitionReview" @click="verifyTransition">Verify transition</v-btn>
            <v-alert v-if="transitionReview" type="success" variant="tonal" class="mt-3" role="status">
              Transition verified. This release is still a draft. Click Publish release below to make the installer and payload available.
              <details class="mt-2"><summary>Signing certificate</summary><p class="hash">{{ transitionSigner }}</p></details>
            </v-alert>
            <p class="text-caption mt-2">Existing shell streams keep their current status. Retire them explicitly from Paravoid → Shells and streams when appropriate.</p>
          </section>
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
          <v-btn color="primary" :loading="busy" :disabled="dirty || busy || (changesMode && !transitionReview) || (selected.distribution_mode === 'paravoid' && !bootstrapVpk)" @click="publish">Publish release</v-btn>
        </template>
        <v-btn v-else color="warning" :loading="busy" :disabled="busy" @click="withdraw">Withdraw release</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ParavoidManagement from './ParavoidManagement.vue'
import { api, type App, type AppVersion, type PublicationEvent, type DistributionReview } from '@/services/api'

const props = defineProps<{ app: App }>()
const emit = defineEmits<{ changed: []; upload: [] }>()
const tab = ref('releases')
const selected = ref<AppVersion | null>(null)
const revision = ref(0)
const reviewedMode = ref('normal')
const hasPublished = ref(false)
const bootstrapMode = ref('')
const bootstrapVpk = ref<string>()
const bootstrapChoices = ref<{ title: string; value: string }[]>([])
const transitionReview = ref<string | undefined>()
const transitionSigner = ref('')
const emptyMigration = () => ({ tested_upgrade: false, database_preserved: false, authentication_preserved: false, files_preserved: false, evidence: '' })
const migration = ref(emptyMigration())
const migrationReady = computed(() => migration.value.tested_upgrade && migration.value.database_preserved && migration.value.authentication_preserved && migration.value.files_preserved && migration.value.evidence.trim().length > 0)
const changesMode = computed(() => (hasPublished.value || reviewedMode.value === 'paravoid') && !!selected.value && (selected.value.distribution_mode ?? 'normal') !== reviewedMode.value)
watch(migration, () => { transitionReview.value = undefined }, { deep: true })
const notes = ref('')
const isBeta = ref(false)
const replaceLatest = ref(false)
const busy = ref(false)
const error = ref('')
const dialogError = ref('')
const history = ref<PublicationEvent[]>([])
const historyLoading = ref(false)
const migrationHistory = ref<DistributionReview[]>([])
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
    bootstrapVpk.value = undefined
    bootstrapChoices.value = []
    bootstrapMode.value = ''
    if (release.distribution_mode === 'paravoid') {
      const distribution = await api.getAppDistribution(props.app.package_name)
      if (distribution.publication_revision !== (fresh.publication_revision ?? 0)) throw new Error('Distribution changed. Review the installer again.')
      const installer = distribution.installers?.find(i => i.installer_version === release.version_code)
      const contractId = installer?.contract_id
      const contract = distribution.contracts.find(c => c.contract_id === contractId)
      bootstrapMode.value = contract?.bootstrap ?? ''
      if (bootstrapMode.value === 'embedded') bootstrapVpk.value = installer?.embedded_vpk_id ?? undefined
      bootstrapChoices.value = distribution.releases.filter(v => v.contract_id === contractId && v.validation_state === 'verified' && ['draft', 'published'].includes(v.publication_state))
        .map(v => ({ title: `Payload ${v.payload_version} · ${v.release_id} · ${v.publication_state}`, value: v.id }))
    }
    hasPublished.value = fresh.versions.some(v => state(v) !== 'draft')
    selected.value = release
    reviewedMode.value = fresh.distribution_mode ?? 'normal'
    migration.value = emptyMigration()
    transitionReview.value = undefined
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
    transitionReview.value = undefined
    await api.saveDraft(props.app.package_name, selected.value.version_code, notes.value, isBeta.value)
    const fresh = await api.getAdminApp(props.app.package_name)
    selected.value = fresh.versions.find(v => v.version_code === selected.value?.version_code) ?? null
    revision.value = fresh.publication_revision ?? 0
    emit('changed')
  } catch (cause) { dialogError.value = message(cause) }
  finally { busy.value = false }
}

async function verifyTransition() {
  if (!selected.value || !migrationReady.value || dirty.value || isBeta.value || busy.value) return
  busy.value = true
  dialogError.value = ''
  try {
    const result = await api.reviewDistributionTransition(props.app.package_name, selected.value.version_code, revision.value, migration.value)
    transitionReview.value = result.id
    transitionSigner.value = result.signer_sha256
    revision.value = result.publication_revision
    emit('changed')
  } catch (cause) { dialogError.value = message(cause) }
  finally { busy.value = false }
}

async function publish() {
  if (!selected.value || dirty.value || busy.value) return
  if (changesMode.value && !transitionReview.value) return
  await mutate(() => changesMode.value
    ? api.publishRelease(props.app.package_name, selected.value!.version_code, revision.value, replaceLatest.value, transitionReview.value, bootstrapVpk.value)
    : api.publishRelease(props.app.package_name, selected.value!.version_code, revision.value, replaceLatest.value, undefined, bootstrapVpk.value))
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
  try { [history.value, migrationHistory.value] = await Promise.all([api.getPublicationHistory(props.app.package_name), api.getDistributionReviews(props.app.package_name)]) }
  catch (cause) { error.value = message(cause) }
  finally { historyLoading.value = false }
}
watch(() => props.app.package_name, () => { selected.value = null; history.value = []; error.value = '' })
watch(tab, value => { if (value === 'history') void loadHistory() })
</script>

<style scoped>
.hash { overflow-wrap: anywhere; font-family: monospace; font-size: 0.8rem; }
</style>
