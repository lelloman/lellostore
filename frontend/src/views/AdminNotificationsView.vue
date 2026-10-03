<template>
  <DefaultLayout>
    <div class="notifications-heading mb-6">
      <div>
        <p class="page-kicker mb-2">Administration</p>
        <h1 class="page-title">Notifications</h1>
        <p class="text-medium-emphasis mt-3 mb-0">Manage apps registered to send push notifications through LelloStore.</p>
      </div>
      <div class="heading-actions">
        <v-btn icon="mdi-refresh" variant="text" aria-label="Refresh notifications" :loading="busy" @click="refresh" />
        <v-btn color="primary" prepend-icon="mdi-plus" :disabled="busy" @click="openInvitation">Create invitation</v-btn>
      </div>
    </div>
    <v-alert v-if="error" type="error" class="mb-4" closable @click:close="error = ''">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4" closable @click:close="notice = ''">{{ notice }}</v-alert>
    <v-skeleton-loader v-if="!overview && busy" type="heading, table-row@4" />
    <template v-if="overview">
      <div class="notification-summary mb-6">
        <v-card class="surface-panel pa-5"><div class="text-h5">{{ registeredAppCount }}</div><div class="text-body-2 text-medium-emphasis">Registered apps</div></v-card>
        <v-card class="surface-panel pa-5"><div class="text-h5">{{ overview.connections }}</div><div class="text-body-2 text-medium-emphasis">Connected devices</div></v-card>
        <v-card class="surface-panel pa-5"><div class="text-h5">{{ pendingMessages.toLocaleString() }}</div><div class="text-body-2 text-medium-emphasis">Pending deliveries</div></v-card>
      </div>
      <v-card class="surface-panel">
        <div class="pa-5">
          <h2 class="text-h6">Registered apps</h2>
          <p class="text-body-2 text-medium-emphasis mt-1 mb-0">Apps appear here after their backend redeems an invitation. Manage a registration to change delivery policies.</p>
        </div>
        <v-divider />
        <div v-if="!registeredApps.length" class="pa-8 text-center">
          <v-icon icon="mdi-bell-outline" size="40" class="mb-3 text-medium-emphasis" />
          <h3 class="text-subtitle-1">No apps registered yet</h3>
          <p class="text-body-2 text-medium-emphasis mt-2">Create an invitation and share it privately with the app’s backend administrator.</p>
          <v-btn color="primary" variant="tonal" class="mt-3" @click="openInvitation">Create invitation</v-btn>
        </div>
        <v-table v-else>
          <thead><tr><th>Android app</th><th>Registered backend</th><th>Delivery</th><th>Pending deliveries</th><th><span class="sr-only">Actions</span></th></tr></thead>
          <tbody>
            <tr v-for="app in registeredApps" :key="`${app.sender.id}/${app.package}`">
              <td class="py-4"><div class="font-weight-medium">{{ app.package }}</div><div class="text-caption text-medium-emphasis">{{ app.types }} message types</div></td>
              <td>{{ app.sender.name }} <v-chip v-if="app.sender.id === 'lellostore'" size="x-small" class="ml-2" variant="tonal">Built-in</v-chip></td>
              <td><v-chip :color="app.sender.enabled ? 'success' : undefined" size="small" variant="tonal">{{ app.sender.enabled ? 'Enabled' : 'Disabled' }}</v-chip></td>
              <td>{{ app.sender.pending.toLocaleString() }}<div class="text-caption text-medium-emphasis">Across this backend’s apps</div></td>
              <td><v-btn variant="text" :disabled="busy" :aria-label="`Manage ${app.package}`" @click="manage(app.sender)">Manage</v-btn></td>
            </tr>
          </tbody>
        </v-table>
      </v-card>
      <p v-if="overview.outcomes.length" class="text-caption text-medium-emphasis mt-4">Delivery outcomes: {{ overview.outcomes.map(o => `${o.count.toLocaleString()} ${o.state}`).join(' · ') }}</p>
    </template>

    <v-dialog v-model="invitationDialog" max-width="620" :persistent="busy" @after-leave="clearInvitation">
      <v-card class="pa-6">
        <div class="dialog-heading mb-3"><h2 class="text-h6">Create invitation</h2><v-btn icon="mdi-close" variant="text" aria-label="Close invitation" :disabled="busy" @click="invitationDialog = false" /></div>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <template v-if="!invitation">
          <p class="text-body-2 text-medium-emphasis mb-5">Invite an app’s backend to register for push delivery. The invitation authorizes the Android package and signing certificate below.</p>
          <v-form @submit.prevent="invite">
            <v-text-field v-model="name" label="App / backend name" placeholder="Talìa" :disabled="busy" />
            <v-text-field v-model="packageName" label="Android package" placeholder="com.lelloman.talia" :disabled="busy" />
            <v-text-field v-model="certificate" label="Signing certificate SHA-256" hint="64 hexadecimal characters" persistent-hint :disabled="busy" />
            <div class="dialog-actions mt-5"><v-btn variant="text" :disabled="busy" @click="invitationDialog = false">Cancel</v-btn><v-btn color="primary" type="submit" :loading="busy" :disabled="!canInvite">Create invitation</v-btn></div>
          </v-form>
        </template>
        <template v-else>
          <v-alert type="success" variant="tonal" class="mb-4">Invitation created</v-alert>
          <p class="text-body-2 mb-4">This invitation expires in 30 minutes and can register one backend. Share it privately with the backend administrator. The app will appear in the list after registration.</p>
          <v-textarea :model-value="invitation" readonly label="Invitation" rows="3" />
          <p class="text-caption text-medium-emphasis">Save it before closing this dialog.</p>
          <div class="dialog-actions mt-4"><v-btn variant="text" @click="copyInvitation">Copy invitation</v-btn><v-btn color="primary" @click="invitationDialog = false">Done</v-btn></div>
          <p v-if="copied" class="text-body-2 mt-3" role="status">Invitation copied.</p>
        </template>
      </v-card>
    </v-dialog>

    <v-dialog :model-value="!!selectedSender" max-width="1000" scrollable :persistent="busy" @update:model-value="closeManagement">
      <v-card v-if="selectedSender" class="pa-5">
        <v-card-text>
          <div class="dialog-heading"><p class="text-overline">Manage registration</p><v-btn icon="mdi-close" variant="text" aria-label="Close registration" :disabled="busy" @click="closeManagement" /></div>
          <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
          <h2 class="text-h6">{{ selectedSender.name }}</h2>
          <p class="text-body-2 mb-3">{{ selectedSender.pending }} pending · {{ selectedSender.queued_bytes.toLocaleString() }} queued bytes</p>
          <v-switch v-model="selectedSender.enabled" label="Allow delivery" color="primary" />
          <p class="text-body-2 text-medium-emphasis mb-4">Policies and limits apply to all apps registered with this backend.</p>
          <h3 class="text-subtitle-1 mb-2">Registered Android apps</h3>
          <div v-for="app in selectedSender.applications" :key="app.package" class="mb-2">
            <v-text-field v-model="app.package" label="Package" :disabled="selectedSender.id === 'lellostore'" />
            <v-text-field :model-value="app.certificates.join(', ')" :disabled="selectedSender.id === 'lellostore'" label="Approved signing certificates (comma separated)" @update:model-value="app.certificates = String($event).split(',').map(v => v.trim()).filter(Boolean)" />
          </div>
          <v-btn v-if="selectedSender.id !== 'lellostore'" variant="text" @click="selectedSender.applications.push({ package: '', certificates: [] })">Add application</v-btn>
          <h3 class="text-subtitle-1 mt-4">Sender defaults</h3>
          <v-table>
            <thead><tr><th>Application / type</th><th>Levels</th><th>Strategy</th><th>Retention</th></tr></thead>
            <tbody><tr v-for="type in selectedSender.manifest.types" :key="`${type.application}/${type.name}`">
              <td>{{ type.application }} / {{ type.name }}</td><td>{{ type.levels.join(', ') }}</td>
              <td>{{ type.default.strategy }}</td><td>{{ type.default.ttl_seconds === null ? 'Until delivered' : `${type.default.ttl_seconds} seconds` }}</td>
            </tr></tbody>
          </v-table>
          <h3 class="text-subtitle-1 mt-4">Administrator overrides</h3>
          <p class="text-body-2 mb-3">The first matching override wins. All listed tags must match. Leave type or level blank to match any.</p>
          <div v-for="(rule, i) in selectedSender.overrides" :key="i" class="pa-3 mb-3 border rounded">
            <v-text-field v-model="rule.application" label="Application package" />
            <v-text-field v-model="rule.message_type" label="Message type" clearable />
            <v-text-field v-model="rule.level" label="Level" clearable />
            <v-text-field :model-value="rule.tags.join(', ')" label="Tags (comma separated)" @update:model-value="rule.tags = String($event).split(',').map(v => v.trim()).filter(Boolean)" />
            <v-select v-model="rule.policy.strategy" label="Strategy" :items="strategies" />
            <v-text-field :model-value="rule.policy.ttl_seconds" label="Retention in seconds (blank means until delivered)" type="number" min="1" @update:model-value="rule.policy.ttl_seconds = $event === '' || $event === null ? null : Number($event)" />
            <v-btn variant="text" :disabled="i === 0" @click="moveRule(selectedSender, i)">Move up</v-btn>
            <v-btn variant="text" color="error" @click="selectedSender.overrides.splice(i, 1)">Remove rule</v-btn>
          </div>
          <v-btn variant="text" @click="selectedSender.overrides.push({ application: selectedSender.applications[0]?.package ?? 'com.lelloman.store', message_type: null, level: null, tags: [], policy: { strategy: 'queue', ttl_seconds: 604800 } })">Add override</v-btn>
          <v-row class="mt-3">
            <v-col cols="12" md="3"><v-text-field v-model.number="selectedSender.max_pending" type="number" label="Pending delivery limit" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="selectedSender.max_bytes" type="number" label="Queue byte limit" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="selectedSender.rate" type="number" label="Messages per second" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="selectedSender.burst" type="number" label="Burst limit" /></v-col>
          </v-row>
          <v-btn color="primary" :disabled="busy" class="mr-3" @click="save(selectedSender)">Save policies</v-btn>
          <v-btn :disabled="busy" class="mr-3" @click="cancel(selectedSender)">Cancel pending messages</v-btn>
          <v-btn v-if="selectedSender.id !== 'lellostore'" :disabled="busy" class="mr-3" @click="rotate(selectedSender)">Rotate credential</v-btn>
          <v-btn v-if="selectedSender.id !== 'lellostore'" color="error" :disabled="busy" @click="revoke(selectedSender)">Revoke registration</v-btn>
          <v-alert v-if="rotated?.id === selectedSender.id" class="mt-3" type="info">
            Install this credential in the sender backend. Previous credentials expire within 24 hours.
            <v-textarea :model-value="rotated.credential" readonly label="New credential" rows="2" />
            <v-btn @click="rotated = null">Hide credential</v-btn>
          </v-alert>
        </v-card-text>
      </v-card>
    </v-dialog>
  </DefaultLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const invitationDialog = ref(false)
const copied = ref(false)
const selectedSender = ref<Sender | null>(null)
const registeredApps = computed(() => (overview.value?.senders ?? []).flatMap(sender =>
  sender.applications.map(app => ({ package: app.package, sender, types: sender.manifest.types.filter(type => (type.application === app.package || type.application === '*')).length }))))
const registeredAppCount = computed(() => new Set(registeredApps.value.map(app => app.package)).size)
const pendingMessages = computed(() => (overview.value?.senders ?? []).reduce((sum, sender) => sum + sender.pending, 0))
const canInvite = computed(() => !!name.value.trim() && !!packageName.value.trim() && /^[0-9a-f]{64}$/i.test(certificate.value.trim()))
function clearInvitation() { invitation.value = ''; name.value = ''; packageName.value = ''; certificate.value = ''; copied.value = false }
function openInvitation() { clearInvitation(); error.value = ''; invitationDialog.value = true }
function manage(sender: Sender) { selectedSender.value = JSON.parse(JSON.stringify(sender)) as Sender; rotated.value = null; error.value = '' }
function closeManagement() { if (!busy.value) { selectedSender.value = null; rotated.value = null } }
async function copyInvitation() {
  try { await navigator.clipboard.writeText(invitation.value); copied.value = true }
  catch { error.value = 'Could not copy the invitation. Select and copy it manually.' }
}
const rotated = ref<{ id: string; credential: string } | null>(null)
const strategies = [{ title: 'Queue each message', value: 'queue' }, { title: 'Keep latest state', value: 'latest' }, { title: 'Connected devices only', value: 'online_only' }]
const base = '/api/admin/notifications'
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
async function refresh() { await perform(async () => { overview.value = await request<Overview>(base) }) }
async function invite() { if (!canInvite.value) return; await perform(async () => {
  const result = await request<{ invitation: string }>(`${base}/invitations`, { method: 'POST', body: JSON.stringify({ name: name.value.trim(), applications: [{ package: packageName.value.trim(), certificates: [certificate.value.trim().toLowerCase()] }] }) })
  invitation.value = result.invitation
}) }
function moveRule(sender: Sender, i: number) { const [rule] = sender.overrides.splice(i, 1); if (rule) sender.overrides.splice(i - 1, 0, rule) }
async function save(sender: Sender) { await perform(async () => {
  const { enabled, applications, overrides, max_pending, max_bytes, rate, burst } = sender
  await request(`${base}/senders/${encodeURIComponent(sender.id)}`, { method: 'PUT', body: JSON.stringify({ enabled, applications, overrides: overrides.map(r => ({ ...r, message_type: r.message_type || null, level: r.level || null })), max_pending, max_bytes, rate, burst }) })
  overview.value = await request<Overview>(base)
  selectedSender.value = null
  notice.value = 'Registration saved. New policies apply to future messages.'
}) }
async function cancel(sender: Sender) {
  if (!window.confirm(`Cancel all pending messages from ${sender.name}?`)) return
  await perform(async () => { await request(`${base}/senders/${encodeURIComponent(sender.id)}/cancel`, { method: 'POST' }); overview.value = await request<Overview>(base) })
}
async function revoke(sender: Sender) {
  if (!window.confirm(`Revoke ${sender.name}, its credentials and its subscriptions?`)) return
  await perform(async () => { await request(`${base}/senders/${encodeURIComponent(sender.id)}/revoke`, { method: 'POST' }); overview.value = await request<Overview>(base); selectedSender.value = null; rotated.value = null; notice.value = 'Registration revoked.' })
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

<style scoped>
.notifications-heading, .dialog-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.heading-actions, .dialog-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.notification-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 600px) {
  .notifications-heading { align-items: flex-start; flex-direction: column; }
  .notification-summary { grid-template-columns: 1fr; }
}
</style>
