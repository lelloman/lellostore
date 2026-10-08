<template>
  <DefaultLayout>
    <div class="d-flex align-center justify-space-between mb-6">
      <div><h1 class="page-title">Notifications</h1><p>Approve servers that can send UnifiedPush notifications.</p></div>
      <v-btn :disabled="busy" @click="perform(refresh)">Refresh</v-btn>
    </div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4">{{ notice }}</v-alert>
    <p v-if="overview">{{ overview.connections }} connected devices · {{ registrations }} registrations</p>
    <v-card class="pa-5 mb-6">
      <h2 class="text-h6 mb-3">Approve a server</h2>
      <p class="mb-4">Enter the server's public VAPID key. The private key stays on that server. Recipient apps do not need an account with this store's identity provider.</p>
      <v-form @submit.prevent="approve">
        <v-text-field v-model="name" label="Server name" :disabled="busy" />
        <v-text-field v-model="key" label="VAPID public key" :disabled="busy" hint="87 characters, base64url-encoded P-256 public key" persistent-hint />
        <v-btn type="submit" color="primary" :disabled="busy || !canApprove">Approve server</v-btn>
      </v-form>
    </v-card>
    <v-card class="pa-5">
      <h2 class="text-h6">Approved servers</h2>
      <p v-if="overview && !overview.senders.length">No servers approved yet.</p>
      <v-table><thead><tr><th>Server</th><th>Delivery</th><th>Registrations</th><th>Queued</th><th>Actions</th></tr></thead>
        <tbody><tr v-for="sender in overview?.senders" :key="sender.id">
          <td>{{ sender.name }}</td><td>{{ sender.enabled ? 'Enabled' : 'Suspended' }}</td>
          <td>{{ sender.registrations }}</td><td>{{ sender.pending }} messages · {{ sender.queued_bytes }} bytes</td>
          <td><v-btn :disabled="busy" :aria-label="`Manage ${sender.name}`" @click="manage(sender)">Manage</v-btn></td>
        </tr></tbody>
      </v-table>
      <p class="mt-4">{{ overview?.outcomes.map(o => `${o.count} ${o.state}`).join(' · ') }}</p>
    </v-card>
    <v-dialog :model-value="!!selected" max-width="800" :persistent="busy" @update:model-value="close">
      <v-card v-if="selected" class="pa-6">
        <h2 class="text-h6 mb-4">Manage server</h2>
        <v-text-field v-model="selected.name" label="Server name" :disabled="busy" />
        <v-switch v-model="selected.enabled" label="Allow delivery" :disabled="busy" />
        <p class="mb-3">Suspension pauses delivery; queued messages still expire.</p>
        <v-text-field v-model.number="selected.rate" label="Messages per second" type="number" :disabled="busy" />
        <v-text-field v-model.number="selected.burst" label="Burst limit" type="number" :disabled="busy" />
        <v-text-field v-model.number="selected.max_pending" label="Pending message limit" type="number" :disabled="busy" />
        <v-text-field v-model.number="selected.max_bytes" label="Queued byte limit" type="number" :disabled="busy" />
        <v-btn color="primary" :disabled="busy" @click="save">Save settings</v-btn>
        <h3 class="text-subtitle-1 mt-5">Signing keys</h3>
        <div v-for="item in selected.keys" :key="item.key" class="my-3" style="overflow-wrap: anywhere">
          <code>{{ item.key }}</code>
          <span v-if="item.revoked"> · Revoked</span>
          <v-btn v-else color="error" :disabled="busy" @click="revokeCandidate = item.key">Revoke key</v-btn>
        </div>
        <v-alert v-if="revokeCandidate" type="warning" class="mb-3">
          Revoking this key deletes its pending messages and invalidates its endpoints. Apps must register with a new approved key.
          <v-btn :disabled="busy" @click="revoke">Confirm revocation</v-btn>
          <v-btn :disabled="busy" @click="revokeCandidate = ''">Cancel</v-btn>
        </v-alert>
        <v-text-field v-model="replacementKey" label="Additional VAPID public key" :disabled="busy" />
        <p class="mb-3">Approve the replacement first. Migrate app registrations before revoking the old key.</p>
        <v-btn :disabled="busy || !validKey(replacementKey)" @click="addKey">Approve additional key</v-btn>
        <v-btn class="mt-4" :disabled="busy" aria-label="Close server" @click="close">Close</v-btn>
      </v-card>
    </v-dialog>
  </DefaultLayout>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { request } from '@/services/api'
type Sender = { id: string; name: string; enabled: boolean; keys: { key: string; revoked: boolean }[]; rate: number; burst: number; max_pending: number; max_bytes: number; pending: number; queued_bytes: number; registrations: number }
type Overview = { connections: number; senders: Sender[]; outcomes: { state: string; count: number }[] }
const base = '/api/admin/notifications'
const overview = ref<Overview | null>(null)
const selected = ref<Sender | null>(null)
const name = ref(''), key = ref(''), replacementKey = ref(''), revokeCandidate = ref('')
const busy = ref(false), error = ref(''), notice = ref('')
const validKey = (value: string) => /^[A-Za-z0-9_-]{87}$/.test(value.trim())
const canApprove = computed(() => !!name.value.trim() && validKey(key.value))
const registrations = computed(() => overview.value?.senders.reduce((n, s) => n + s.registrations, 0) ?? 0)
async function refresh() { overview.value = await request<Overview>(base) }
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
function manage(sender: Sender) { selected.value = JSON.parse(JSON.stringify(sender)) as Sender; replacementKey.value = ''; revokeCandidate.value = '' }
function close() { if (!busy.value) { selected.value = null; revokeCandidate.value = ''; replacementKey.value = '' } }
async function approve() { if (!canApprove.value) return; await perform(async () => {
  await request(`${base}/senders`, { method: 'POST', body: JSON.stringify({ name: name.value.trim(), key: key.value.trim() }) })
  name.value = ''; key.value = ''; await refresh(); notice.value = 'Server approved'
}) }
async function save() { await perform(async () => {
  const s = selected.value!;
  await request(`${base}/senders/${s.id}`, { method: 'PUT', body: JSON.stringify({ name: s.name, enabled: s.enabled, rate: s.rate, burst: s.burst, max_pending: s.max_pending, max_bytes: s.max_bytes }) })
  await refresh(); selected.value = null; notice.value = 'Server updated'
}) }
async function addKey() { await perform(async () => {
  const id = selected.value!.id
  await request(`${base}/senders/${id}/keys`, { method: 'POST', body: JSON.stringify({ key: replacementKey.value.trim() }) })
  await refresh(); manage(overview.value!.senders.find(s => s.id === id)!); notice.value = 'Key approved'
}) }
async function revoke() { await perform(async () => {
  const id = selected.value!.id
  await request(`${base}/senders/${id}/keys/${encodeURIComponent(revokeCandidate.value)}`, { method: 'DELETE' })
  await refresh(); manage(overview.value!.senders.find(s => s.id === id)!); notice.value = 'Key revoked'
}) }
onMounted(() => perform(refresh))
</script>
