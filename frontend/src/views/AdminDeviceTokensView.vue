<template>
  <DefaultLayout>
    <div class="d-flex align-center justify-space-between mb-6">
      <div>
        <h1 class="page-title">Device tokens</h1>
        <p>Let an unattended device, such as a TV box, use the Store without a person signing in.</p>
      </div>
      <div class="d-flex" style="gap: 8px">
        <v-btn color="primary" prepend-icon="mdi-television-play" :to="{ name: 'approve-device-admin' }">Approve a device</v-btn>
        <v-btn :disabled="busy" @click="perform(refresh)">Refresh</v-btn>
      </div>
    </div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4">{{ notice }}</v-alert>
    <v-alert v-if="secret" type="warning" class="mb-6" data-test="secret">
      <p class="mb-2"><strong>Provision this token on the device now, it is shown only once</strong> and cannot be recovered. For a PezzotTV box run <code>LELLOSTORE_DEVICE_TOKEN=&lt;token&gt; setup-tv-box.sh</code>.</p>
      <code class="d-block mb-3" style="overflow-wrap: anywhere">{{ secret }}</code>
      <div class="mb-3" data-test="device-token-qr">
        <img v-if="qr" :src="qr" alt="QR code with the device token for PezzotTV Companion" width="240" height="240" class="d-block" style="background: #fff">
        <p v-else-if="qrError" class="text-body-2">{{ qrError }}</p>
        <p class="text-caption mt-1">Scan with PezzotTV Companion</p>
      </div>
      <v-btn :disabled="busy" prepend-icon="mdi-content-copy" @click="copySecret">Copy token</v-btn>
      <v-btn class="ml-2" variant="text" @click="dismissSecret">I provisioned it</v-btn>
    </v-alert>
    <v-card class="pa-5 mb-6">
      <h2 class="text-h6 mb-3">Create a token</h2>
      <p class="mb-4">A device token acts as its own Store user: it sees exactly the apps its groups and direct grants allow, and cannot reach any administration.</p>
      <v-form @submit.prevent="create">
        <v-text-field v-model="name" label="Token name" :disabled="busy" hint="For example: Living room TV box" persistent-hint />
        <v-text-field v-model="expiresAt" label="Expires on (optional)" type="date" :disabled="busy" />
        <v-select
          v-model="groupIds"
          label="Groups"
          :items="groupOptions"
          item-title="title"
          item-value="value"
          multiple
          chips
          :disabled="busy"
          hint="The device gets the access of every selected group"
          persistent-hint
        />
        <v-btn type="submit" color="primary" class="mt-4" :disabled="busy || !canCreate">Create token</v-btn>
      </v-form>
    </v-card>
    <v-card class="pa-5">
      <div class="d-flex flex-wrap align-center justify-space-between" style="gap: 8px">
        <h2 class="text-h6">Tokens</h2>
        <div class="d-flex flex-wrap align-center" style="gap: 8px">
          <v-switch v-model="showRevoked" :label="`Show revoked (${revokedCount})`" density="compact" hide-details color="primary" />
          <v-btn v-if="revokedCount" variant="text" color="error" :disabled="busy" @click="purgeAllCandidate = true">Remove all revoked</v-btn>
        </div>
      </div>
      <p v-if="!tokens.length">No device tokens yet.</p>
      <p v-else-if="!visibleTokens.length">No active device tokens. {{ revokedCount }} revoked token(s) are hidden.</p>
      <v-table v-else>
        <thead><tr><th>Name</th><th>Groups</th><th>State</th><th>Last used</th><th>Actions</th></tr></thead>
        <tbody>
          <tr v-for="token in visibleTokens" :key="token.id">
            <td>{{ token.name }}</td>
            <td>
              <span v-if="!token.groups.length">None</span>
              <v-chip v-for="group in token.groups" :key="group.id" size="small" class="mr-1">{{ group.name }}</v-chip>
            </td>
            <td>{{ state(token) }}</td>
            <td>{{ token.last_used_at ?? 'Never' }}</td>
            <td>
              <v-btn
                variant="text"
                :to="{ name: 'access-admin', query: { subject: token.subject } }"
                :aria-label="`Manage access of ${token.name}`"
              >Manage access</v-btn>
              <v-btn v-if="!token.revoked_at" color="error" :disabled="busy" :aria-label="`Revoke ${token.name}`" @click="revokeCandidate = token">Revoke</v-btn>
              <v-btn v-else color="error" variant="text" :disabled="busy" :aria-label="`Delete ${token.name}`" @click="deleteCandidate = token">Delete</v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>
    <v-alert v-if="revokeCandidate" type="warning" class="mt-4">
      Revoke “{{ revokeCandidate.name }}”? The device loses Store access immediately.
      <v-btn :disabled="busy" @click="revoke">Confirm revocation</v-btn>
      <v-btn :disabled="busy" @click="revokeCandidate = null">Cancel</v-btn>
    </v-alert>
    <v-alert v-if="deleteCandidate" type="warning" class="mt-4">
      Delete “{{ deleteCandidate.name }}” permanently? Its grants and group memberships are removed too.
      <v-btn :disabled="busy" @click="purge">Confirm deletion</v-btn>
      <v-btn :disabled="busy" @click="deleteCandidate = null">Cancel</v-btn>
    </v-alert>
    <v-alert v-if="purgeAllCandidate" type="warning" class="mt-4">
      Delete all {{ revokedCount }} revoked token(s) permanently, with their grants and group memberships?
      <v-btn :disabled="busy" @click="purgeAll">Confirm removal</v-btn>
      <v-btn :disabled="busy" @click="purgeAllCandidate = false">Cancel</v-btn>
    </v-alert>
  </DefaultLayout>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { api, request, type AppGroup } from '@/services/api'
type TokenGroup = { id: number; name: string }
type Token = { id: string; name: string; subject: string; groups: TokenGroup[]; created_by: string; created_at: string; expires_at: string | null; last_used_at: string | null; revoked_at: string | null }
const base = '/api/admin/device-tokens'
const tokens = ref<Token[]>([])
const groups = ref<AppGroup[]>([])
const name = ref(''), expiresAt = ref(''), secret = ref('')
const groupIds = ref<number[]>([])
// The QR encodes the secret, so it lives only in memory beside it and goes away with it.
const qr = ref(''), qrError = ref('')
let qrGeneration = 0
const revokeCandidate = ref<Token | null>(null), deleteCandidate = ref<Token | null>(null)
const purgeAllCandidate = ref(false)
// Revoked tokens are kept for the record but hidden unless asked for.
const showRevoked = ref(false)
const revokedCount = computed(() => tokens.value.filter(token => token.revoked_at).length)
const visibleTokens = computed(() => showRevoked.value ? tokens.value : tokens.value.filter(token => !token.revoked_at))
const busy = ref(false), error = ref(''), notice = ref('')
const canCreate = computed(() => !!name.value.trim())
const groupOptions = computed(() => groups.value.map(group => ({ title: group.name, value: group.id })))
function state(token: Token) {
  if (token.revoked_at) return `Revoked ${token.revoked_at}`
  if (token.expires_at) return `Expires ${token.expires_at}`
  return 'Active'
}
async function refresh() {
  const [list, groupResponse] = await Promise.all([request<Token[]>(base), api.getAppGroups()])
  tokens.value = list; groups.value = groupResponse.groups
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
async function create() { if (!canCreate.value) return; await perform(async () => {
  const body: { name: string; expires_at?: string; group_ids?: number[] } = { name: name.value.trim() }
  // A date input is a local calendar day; the token stays valid through its end.
  if (expiresAt.value) body.expires_at = new Date(`${expiresAt.value}T23:59:59`).toISOString()
  if (groupIds.value.length) body.group_ids = [...groupIds.value]
  const created = await request<Token & { secret: string }>(base, { method: 'POST', body: JSON.stringify(body) })
  showSecret(created.name, created.secret)
  name.value = ''; expiresAt.value = ''; groupIds.value = []
  await refresh(); notice.value = 'Token created'
}) }
function deviceTokenPayload(tokenName: string, token: string) {
  const serverUrl = window.location.origin.replace(/\/+$/, '')
  return JSON.stringify({ version: 1, server_url: serverUrl, name: tokenName, token })
}
function showSecret(tokenName: string, token: string) {
  secret.value = token; qr.value = ''; qrError.value = ''
  const generation = ++qrGeneration
  QRCode.toDataURL(deviceTokenPayload(tokenName, token), { width: 240, margin: 4, errorCorrectionLevel: 'M', color: { dark: '#000000', light: '#ffffff' } })
    .then(url => { if (generation === qrGeneration) qr.value = url })
    .catch(() => { if (generation === qrGeneration) qrError.value = 'Could not create the QR code; copy the token instead' })
}
function dismissSecret() {
  qrGeneration++; secret.value = ''; qr.value = ''; qrError.value = ''
}
async function copySecret() {
  try { await navigator.clipboard.writeText(secret.value); notice.value = 'Token copied' } catch { error.value = 'Copy failed; select the token and copy it manually' }
}
async function revoke() { await perform(async () => {
  const token = revokeCandidate.value!
  await request(`${base}/${encodeURIComponent(token.id)}`, { method: 'DELETE' })
  revokeCandidate.value = null; await refresh(); notice.value = 'Token revoked'
}) }
async function purge() { await perform(async () => {
  const token = deleteCandidate.value!
  await request(`${base}/${encodeURIComponent(token.id)}/purge`, { method: 'POST' })
  deleteCandidate.value = null; await refresh(); notice.value = 'Token deleted'
}) }
async function purgeAll() { await perform(async () => {
  const result = await request<{ deleted: number }>(`${base}/purge-revoked`, { method: 'POST' })
  purgeAllCandidate.value = false; await refresh(); notice.value = `${result.deleted} revoked token(s) deleted`
}) }
onMounted(() => perform(refresh))
</script>
