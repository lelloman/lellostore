<template>
  <div class="pa-4">
    <div class="d-flex align-center mb-4"><h3>Paravoid distribution</h3><v-spacer /><v-btn :loading="busy" @click="perform(refresh)">Refresh</v-btn></div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="info" class="mb-4">{{ notice }}</v-alert>
    <template v-if="data">
      <p class="mb-4">Current distribution: {{ data.distribution_mode }}. Payload versions are independent of APK versions.</p>
      <v-tabs v-model="section"><v-tab value="payloads">Payloads</v-tab><v-tab value="contracts">Shells and streams</v-tab><v-tab value="grants">Issued access</v-tab><v-tab value="history">History</v-tab></v-tabs>
      <section v-if="section === 'payloads'" class="mt-4">
        <v-alert v-if="!data.contracts.length" type="info">A shell APK must register its pinned contract before you can upload payloads.</v-alert>
        <div v-else>
          <v-select v-model="contractId" label="Target shell contract" :items="data.contracts.map(c => ({ title: `APK ${c.installer_version} · ${c.channel} · ${c.contract_id.slice(0, 12)}`, value: c.contract_id }))" />
          <v-file-input v-model="file" accept=".vpk" label="VPK file" :disabled="busy" />
          <v-btn :disabled="busy || !file || !contractId" @click="upload">Upload payload draft</v-btn>
          <p class="text-caption mt-2 mb-4">Validation continues on the server. Check Uploads for progress and failures.</p>
        </div>
        <v-card v-for="release in data.releases" :key="release.id" class="mb-3" :title="`Payload ${release.payload_version} · ${release.release_id}`" :subtitle="`${release.publication_state} · ${release.validation_state} · ${size(release.archive_size)}`">
          <v-card-actions><v-btn @click="review(release)">Review</v-btn><v-btn :disabled="busy" @click="download(release)">Download original</v-btn></v-card-actions>
        </v-card>
        <p v-if="!data.releases.length" class="mt-4">No payload releases yet.</p>
      </section>
      <section v-if="section === 'contracts'" class="mt-4">
        <p class="mb-4">Each shell pins its endpoint, channel, trust keys and exact contract. Retiring a stream tells installed shells that an APK update is required; it does not uninstall or disable accepted offline payloads.</p>
        <v-card v-for="contract in data.contracts" :key="contract.contract_id" class="mb-3" :title="`APK ${contract.installer_version} · ${contract.channel}`" :subtitle="`${contract.bootstrap} · ${contract.authentication} · ${contract.verification_state}`">
          <v-card-text><p class="hash">{{ contract.contract_id }}</p><p>{{ contract.base_url }}</p><p>Stream: {{ streamFor(contract.contract_id)?.status ?? 'not published' }} · revision {{ streamFor(contract.contract_id)?.revision ?? 0 }}</p></v-card-text>
          <v-card-actions><v-btn :disabled="busy || contract.verification_state !== 'verified'" @click="pendingStream = contract.contract_id">{{ streamFor(contract.contract_id)?.status === 'retired' ? 'Reactivate stream' : 'Retire stream' }}</v-btn></v-card-actions>
        </v-card>
      </section>
      <section v-if="section === 'grants'" class="mt-4">
        <p class="mb-4">Access is tied to the acquiring user's current app/group permissions. Revoking a grant blocks future requests, including resumed downloads. An already accepted offline payload keeps working. Request counts do not prove payload activation.</p>
        <v-card v-for="grant in data.grants" :key="grant.id" class="mb-3" :title="grant.user_subject" :subtitle="`APK ${grant.installer_version} · ${grant.revoked_at ? 'Revoked' : 'Issued'}`">
          <v-card-text><p>Grant {{ grant.id }}</p><p>{{ grant.request_count }} authorized requests · Last request: {{ grant.last_used_at ? new Date(grant.last_used_at * 1000).toLocaleString() : 'None' }}</p></v-card-text>
          <v-card-actions><v-btn :disabled="busy || !!grant.revoked_at" color="warning" @click="pendingGrant = grant.id">Revoke access</v-btn></v-card-actions>
        </v-card>
        <p v-if="!data.grants.length">No grants issued yet. Showing the most recent 200.</p>
      </section>
      <v-list v-if="section === 'history'"><v-list-item v-for="event in data.events" :key="event.id" :title="event.action" :subtitle="`${event.actor_subject} · ${event.created_at} · revision ${event.revision}`" /></v-list>
    </template>
    <v-dialog :model-value="!!selected" max-width="760" :persistent="busy" @update:model-value="v => { if (!v) selected = null }">
      <v-card v-if="selected" :title="`Review payload ${selected.payload_version}`">
        <v-card-text>
          <v-alert v-if="error" type="error" class="mb-3">{{ error }}</v-alert>
          <p>{{ selected.release_id }} · {{ selected.publication_state }}</p>
          <p>Android API {{ selected.min_sdk }}{{ selected.max_sdk ? `–${selected.max_sdk}` : '+' }} · {{ selected.abis_json }}</p>
          <v-textarea v-model="notes" label="Release notes" :disabled="busy || selected.publication_state !== 'draft'" />
          <v-alert v-if="selected.validation_state !== 'verified'" type="warning">Payload inspection passed, but verification against the installed shell is pending. This payload cannot be published yet.</v-alert>
          <details class="mt-3"><summary>Validation and signed identity</summary><pre class="hash">{{ selected.validation_report }}</pre><p class="hash">Contract: {{ selected.contract_id }}</p><p class="hash">Archive: {{ selected.archive_sha256 }}</p><p class="hash">Manifest: {{ selected.manifest_sha256 }}</p><p>Signing key: {{ selected.signing_key_id }}</p></details>
        </v-card-text>
        <v-card-actions><v-btn :disabled="busy" @click="selected = null">Close</v-btn><v-spacer />
          <v-btn v-if="selected.publication_state === 'draft'" :disabled="busy || notes === selected.release_notes" @click="save">Save notes</v-btn>
          <v-btn v-if="selected.publication_state === 'draft'" color="primary" :disabled="busy || selected.validation_state !== 'verified' || notes !== selected.release_notes" @click="publish(false)">Publish payload</v-btn>
          <v-btn v-if="selected.publication_state === 'published'" color="warning" :disabled="busy" @click="publish(true)">Withdraw payload</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog :model-value="!!pendingStream || !!pendingGrant" max-width="560" :persistent="busy" @update:model-value="v => { if (!v) cancelAction() }">
      <v-card :title="pendingGrant ? 'Revoke issued access?' : 'Change this shell stream?'">
        <v-card-text><v-alert v-if="error" type="error">{{ error }}</v-alert><p>Already accepted offline payloads remain usable. {{ pendingGrant ? 'Repair requires a newly authorized shell installation.' : 'Installed shells will receive the new stream status on their next check.' }}</p></v-card-text>
        <v-card-actions><v-btn :disabled="busy" @click="cancelAction">Cancel</v-btn><v-spacer /><v-btn :loading="busy" color="warning" @click="confirmAction">Confirm</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type AppDistribution, type VpkRelease } from '@/services/api'
const props = defineProps<{ packageName: string }>()
const emit = defineEmits<{ changed: [] }>()
const data = ref<AppDistribution | null>(null), selected = ref<VpkRelease | null>(null)
const reviewRevision = ref(0)
const busy = ref(false), error = ref(''), notice = ref(''), section = ref('payloads'), contractId = ref(''), notes = ref('')
const file = ref<File | File[] | null>(null), pendingStream = ref<string | null>(null), pendingGrant = ref<string | null>(null)
const size = (n: number) => `${(n / 1024 / 1024).toFixed(1)} MiB`
const streamFor = (id: string) => data.value?.streams.find(s => s.contract_id === id)
async function refresh() { data.value = await api.getAppDistribution(props.packageName) }
async function perform(action: () => Promise<void>) { busy.value = true; error.value = ''; try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Operation failed' } finally { busy.value = false } }
async function review(release: VpkRelease) { await perform(async () => { await refresh(); const current = data.value?.releases.find(r => r.id === release.id); if (!current) throw new Error('Payload changed. Refresh the list.'); reviewRevision.value = data.value!.publication_revision; selected.value = current; notes.value = current.release_notes }) }
async function upload() { const chosen = Array.isArray(file.value) ? file.value[0] : file.value; if (!chosen) return; await perform(async () => { const job = await api.uploadVpk(props.packageName, contractId.value, chosen); notice.value = `Upload ${job.id} saved. Open Uploads to follow validation.`; file.value = null }) }
async function save() { if (!selected.value || !data.value) return; await perform(async () => { await api.saveVpkNotes(props.packageName, selected.value!.id, reviewRevision.value, notes.value); await refresh(); selected.value = data.value!.releases.find(r => r.id === selected.value!.id) ?? null; reviewRevision.value = data.value!.publication_revision; emit('changed') }) }
async function publish(withdraw: boolean) { if (!selected.value || !data.value) return; await perform(async () => { await api.publishVpk(props.packageName, selected.value!.id, reviewRevision.value, withdraw); selected.value = null; await refresh(); emit('changed') }) }
async function download(release: VpkRelease) { await perform(async () => { const blob = await api.downloadVpk(props.packageName, release); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `${release.release_id}.vpk`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }) }
function cancelAction() { pendingStream.value = null; pendingGrant.value = null }
async function confirmAction() { if (!data.value) return; await perform(async () => { if (pendingGrant.value) await api.revokeParavoidGrant(props.packageName, pendingGrant.value, data.value!.publication_revision); else if (pendingStream.value) await api.setParavoidStream(props.packageName, pendingStream.value, data.value!.publication_revision, streamFor(pendingStream.value)?.status !== 'retired'); cancelAction(); await refresh(); emit('changed') }) }
onMounted(() => perform(refresh))
</script>
<style scoped>.hash { overflow-wrap: anywhere; white-space: pre-wrap; font-family: monospace; }</style>
