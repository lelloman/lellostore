<template>
  <DefaultLayout>
    <h1 class="page-title mb-3">Notifications</h1>
    <p class="mb-6">Register sender services and control how their messages are retained and delivered.</p>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4" closable @click:close="notice = ''">{{ notice }}</v-alert>
    <v-btn :loading="busy" class="mb-4" @click="refresh">Refresh</v-btn>
    <p v-if="overview" class="mb-4">{{ overview.connections }} connected devices · {{ overview.outcomes.map(o => `${o.count} ${o.state}`).join(' · ') }}</p>

    <v-card class="pa-5 mb-6">
      <h2 class="text-h6 mb-3">Invite a sender</h2>
      <v-text-field v-model="name" label="Sender name" placeholder="Talìa" />
      <v-text-field v-model="packageName" label="Recipient Android package" placeholder="com.lelloman.talia" />
      <v-text-field v-model="certificate" label="Signing certificate SHA-256" hint="64 lowercase hexadecimal characters" persistent-hint />
      <v-btn class="mt-3" :disabled="busy || !name || !packageName || !/^[0-9a-f]{64}$/.test(certificate)" @click="invite">Create invitation</v-btn>
      <v-alert v-if="invitation" class="mt-4" type="info">
        This invitation expires in 30 minutes and can enroll one backend. Save it in the sender's private configuration.
        <v-textarea :model-value="invitation" readonly label="Invitation" rows="2" />
        <v-btn @click="invitation = ''">Hide invitation</v-btn>
      </v-alert>
    </v-card>

    <v-card v-for="sender in overview?.senders" :key="sender.id" class="pa-5 mb-6">
      <h2 class="text-h6">{{ sender.name }}</h2>
      <p class="text-body-2 mb-3">{{ sender.pending }} pending · {{ sender.queued_bytes.toLocaleString() }} queued bytes</p>
      <v-switch v-model="sender.enabled" label="Allow delivery" color="primary" />
      <h3 class="text-subtitle-1 mb-2">Approved applications</h3>
      <div v-for="app in sender.applications" :key="app.package" class="mb-2">
        <v-text-field v-model="app.package" label="Package" :disabled="sender.id === 'lellostore'" />
        <v-text-field :model-value="app.certificates.join(', ')" :disabled="sender.id === 'lellostore'" label="Approved signing certificates (comma separated)" @update:model-value="app.certificates = String($event).split(',').map(v => v.trim()).filter(Boolean)" />
      </div>
      <v-btn v-if="sender.id !== 'lellostore'" variant="text" @click="sender.applications.push({ package: '', certificates: [] })">Add application</v-btn>
      <h3 class="text-subtitle-1 mt-4">Sender defaults</h3>
      <v-table>
        <thead><tr><th>Application / type</th><th>Levels</th><th>Strategy</th><th>Retention</th></tr></thead>
        <tbody><tr v-for="type in sender.manifest.types" :key="`${type.application}/${type.name}`">
          <td>{{ type.application }} / {{ type.name }}</td><td>{{ type.levels.join(', ') }}</td>
          <td>{{ type.default.strategy }}</td><td>{{ type.default.ttl_seconds === null ? 'Until delivered' : `${type.default.ttl_seconds} seconds` }}</td>
        </tr></tbody>
      </v-table>
      <h3 class="text-subtitle-1 mt-4">Administrator overrides</h3>
      <p class="text-body-2 mb-3">The first matching override wins. All listed tags must match. Leave type or level blank to match any.</p>
      <div v-for="(rule, i) in sender.overrides" :key="i" class="pa-3 mb-3 border rounded">
        <v-text-field v-model="rule.application" label="Application package" />
        <v-text-field v-model="rule.message_type" label="Message type" clearable />
        <v-text-field v-model="rule.level" label="Level" clearable />
        <v-text-field :model-value="rule.tags.join(', ')" label="Tags (comma separated)" @update:model-value="rule.tags = String($event).split(',').map(v => v.trim()).filter(Boolean)" />
        <v-select v-model="rule.policy.strategy" label="Strategy" :items="strategies" />
        <v-text-field :model-value="rule.policy.ttl_seconds" label="Retention in seconds (blank means until delivered)" type="number" min="1" @update:model-value="rule.policy.ttl_seconds = $event === '' || $event === null ? null : Number($event)" />
        <v-btn variant="text" :disabled="i === 0" @click="moveRule(sender, i)">Move up</v-btn>
        <v-btn variant="text" color="error" @click="sender.overrides.splice(i, 1)">Remove rule</v-btn>
      </div>
      <v-btn variant="text" @click="sender.overrides.push({ application: sender.applications[0]?.package ?? 'com.lelloman.store', message_type: null, level: null, tags: [], policy: { strategy: 'queue', ttl_seconds: 604800 } })">Add override</v-btn>
      <v-row class="mt-3">
        <v-col cols="12" md="3"><v-text-field v-model.number="sender.max_pending" type="number" label="Pending delivery limit" /></v-col>
        <v-col cols="12" md="3"><v-text-field v-model.number="sender.max_bytes" type="number" label="Queue byte limit" /></v-col>
        <v-col cols="12" md="3"><v-text-field v-model.number="sender.rate" type="number" label="Messages per second" /></v-col>
        <v-col cols="12" md="3"><v-text-field v-model.number="sender.burst" type="number" label="Burst limit" /></v-col>
      </v-row>
      <v-btn color="primary" :disabled="busy" class="mr-3" @click="save(sender)">Save policies</v-btn>
      <v-btn :disabled="busy" class="mr-3" @click="cancel(sender)">Cancel pending messages</v-btn>
      <v-btn v-if="sender.id !== 'lellostore'" :disabled="busy" class="mr-3" @click="rotate(sender)">Rotate credential</v-btn>
      <v-btn v-if="sender.id !== 'lellostore'" color="error" :disabled="busy" @click="revoke(sender)">Revoke sender</v-btn>
      <v-alert v-if="rotated?.id === sender.id" class="mt-3" type="info">
        Install this credential in the sender backend. Previous credentials expire within 24 hours.
        <v-textarea :model-value="rotated.credential" readonly label="New credential" rows="2" />
        <v-btn @click="rotated = null">Hide credential</v-btn>
      </v-alert>
    </v-card>
  </DefaultLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { request } from '@/services/api'

type Policy = { strategy: 'queue' | 'latest' | 'online_only'; ttl_seconds: number | null }
type Rule = { application: string; message_type: string | null; level: string | null; tags: string[]; policy: Policy }
type Sender = { id: string; name: string; enabled: boolean; applications: { package: string; certificates: string[] }[]; manifest: { types: { application: string; name: string; levels: string[]; default: Policy }[] }; overrides: Rule[]; max_pending: number; max_bytes: number; rate: number; burst: number; pending: number; queued_bytes: number }
type Overview = { senders: Sender[]; connections: number; outcomes: { state: string; count: number }[] }
const overview = ref<Overview | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const name = ref('')
const packageName = ref('')
const certificate = ref('')
const invitation = ref('')
const rotated = ref<{ id: string; credential: string } | null>(null)
const strategies = [{ title: 'Queue each message', value: 'queue' }, { title: 'Keep latest state', value: 'latest' }, { title: 'Connected devices only', value: 'online_only' }]
const base = '/api/admin/notifications'
async function perform(action: () => Promise<void>) {
  busy.value = true; error.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
async function refresh() { await perform(async () => { overview.value = await request<Overview>(base) }) }
async function invite() { await perform(async () => {
  const result = await request<{ invitation: string }>(`${base}/invitations`, { method: 'POST', body: JSON.stringify({ name: name.value, applications: [{ package: packageName.value, certificates: [certificate.value] }] }) })
  invitation.value = result.invitation
}) }
function moveRule(sender: Sender, i: number) { const [rule] = sender.overrides.splice(i, 1); if (rule) sender.overrides.splice(i - 1, 0, rule) }
async function save(sender: Sender) { await perform(async () => {
  const { enabled, applications, overrides, max_pending, max_bytes, rate, burst } = sender
  await request(`${base}/senders/${encodeURIComponent(sender.id)}`, { method: 'PUT', body: JSON.stringify({ enabled, applications, overrides: overrides.map(r => ({ ...r, message_type: r.message_type || null, level: r.level || null })), max_pending, max_bytes, rate, burst }) })
  notice.value = 'Saved. New policies apply to future publications.'
}) }
async function cancel(sender: Sender) {
  if (!window.confirm(`Cancel all pending messages from ${sender.name}?`)) return
  await perform(async () => { await request(`${base}/senders/${encodeURIComponent(sender.id)}/cancel`, { method: 'POST' }); overview.value = await request<Overview>(base) })
}
async function revoke(sender: Sender) {
  if (!window.confirm(`Revoke ${sender.name}, its credentials and its subscriptions?`)) return
  await perform(async () => { await request(`${base}/senders/${encodeURIComponent(sender.id)}/revoke`, { method: 'POST' }); overview.value = await request<Overview>(base) })
}
async function rotate(sender: Sender) {
  if (!window.confirm(`Rotate ${sender.name}'s credential with a 24-hour overlap?`)) return
  await perform(async () => {
    const credential = Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, '0')).join('')
    await request(`${base}/senders/${encodeURIComponent(sender.id)}/credentials`, { method: 'POST', body: JSON.stringify({ request_id: crypto.randomUUID(), credential }) })
    rotated.value = { id: sender.id, credential }
  })
}
onMounted(refresh)
</script>
