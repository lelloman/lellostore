<template>
  <DefaultLayout>
    <div class="d-flex align-center justify-space-between mb-6">
      <div>
        <h1 class="page-title">Publish tokens</h1>
        <p>Let a release pipeline upload and publish one app without signing in.</p>
      </div>
      <v-btn :disabled="busy" @click="perform(refresh)">Refresh</v-btn>
    </div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4">{{ notice }}</v-alert>
    <v-alert v-if="secret" type="warning" class="mb-6" data-test="secret">
      <p class="mb-2"><strong>Copy this token now.</strong> It is shown only once and cannot be recovered. Store it as a CI secret named <code>LELLOSTORE_PUBLISH_TOKEN</code>.</p>
      <code class="d-block mb-3" style="overflow-wrap: anywhere">{{ secret }}</code>
      <v-btn :disabled="busy" prepend-icon="mdi-content-copy" @click="copySecret">Copy token</v-btn>
      <v-btn class="ml-2" variant="text" @click="secret = ''">I stored it</v-btn>
    </v-alert>
    <v-card class="pa-5 mb-6">
      <h2 class="text-h6 mb-3">Create a token</h2>
      <p class="mb-4">A token can upload drafts, upload payloads, poll its own uploads and publish releases of exactly one app. It cannot reach any other administration.</p>
      <v-form @submit.prevent="create">
        <v-text-field v-model="name" label="Token name" :disabled="busy" hint="For example: Pezzottify release CI" persistent-hint />
        <v-text-field v-model="packageName" label="App package" :disabled="busy" hint="Android application ID, for example com.example.app" persistent-hint />
        <v-text-field v-model="expiresAt" label="Expires on (optional)" type="date" :disabled="busy" />
        <v-btn type="submit" color="primary" :disabled="busy || !canCreate">Create token</v-btn>
      </v-form>
    </v-card>
    <v-card class="pa-5">
      <h2 class="text-h6">Tokens</h2>
      <p v-if="!tokens.length">No publish tokens yet.</p>
      <v-table v-else>
        <thead><tr><th>Name</th><th>App</th><th>State</th><th>Last used</th><th>Recent uploads</th><th>Actions</th></tr></thead>
        <tbody>
          <tr v-for="token in tokens" :key="token.id">
            <td>{{ token.name }}</td>
            <td><code>{{ token.package_name }}</code></td>
            <td>{{ state(token) }}</td>
            <td>{{ token.last_used_at ?? 'Never' }}</td>
            <td>
              <span v-if="!token.recent_uploads.length">None</span>
              <div v-for="upload in token.recent_uploads" :key="upload.id">
                {{ upload.kind.toUpperCase() }} · {{ upload.status }} · {{ upload.created_at }}
              </div>
            </td>
            <td>
              <v-btn v-if="!token.revoked_at" color="error" :disabled="busy" :aria-label="`Revoke ${token.name}`" @click="revokeCandidate = token">Revoke</v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card>
    <v-alert v-if="revokeCandidate" type="warning" class="mt-4">
      Revoke “{{ revokeCandidate.name }}”? Pipelines using it stop working immediately, and its queued uploads are refused.
      <v-btn :disabled="busy" @click="revoke">Confirm revocation</v-btn>
      <v-btn :disabled="busy" @click="revokeCandidate = null">Cancel</v-btn>
    </v-alert>
  </DefaultLayout>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { request } from '@/services/api'
type Upload = { id: string; kind: string; package_name: string | null; status: string; error: string | null; created_at: string }
type Token = { id: string; name: string; package_name: string; created_by: string; created_at: string; expires_at: string | null; last_used_at: string | null; revoked_at: string | null; recent_uploads: Upload[] }
const base = '/api/admin/publish-tokens'
const tokens = ref<Token[]>([])
const name = ref(''), packageName = ref(''), expiresAt = ref(''), secret = ref('')
const revokeCandidate = ref<Token | null>(null)
const busy = ref(false), error = ref(''), notice = ref('')
const canCreate = computed(() => !!name.value.trim() && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName.value.trim()))
function state(token: Token) {
  if (token.revoked_at) return `Revoked ${token.revoked_at}`
  if (token.expires_at) return `Expires ${token.expires_at}`
  return 'Active'
}
async function refresh() { tokens.value = await request<Token[]>(base) }
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
async function create() { if (!canCreate.value) return; await perform(async () => {
  const body: Record<string, string> = { name: name.value.trim(), package_name: packageName.value.trim() }
  // A date input is a local calendar day; the token stays valid through its end.
  if (expiresAt.value) body.expires_at = new Date(`${expiresAt.value}T23:59:59`).toISOString()
  const created = await request<Token & { secret: string }>(base, { method: 'POST', body: JSON.stringify(body) })
  secret.value = created.secret
  name.value = ''; packageName.value = ''; expiresAt.value = ''
  await refresh(); notice.value = 'Token created'
}) }
async function copySecret() {
  try { await navigator.clipboard.writeText(secret.value); notice.value = 'Token copied' } catch { error.value = 'Copy failed; select the token and copy it manually' }
}
async function revoke() { await perform(async () => {
  const token = revokeCandidate.value!
  await request(`${base}/${encodeURIComponent(token.id)}`, { method: 'DELETE' })
  revokeCandidate.value = null; await refresh(); notice.value = 'Token revoked'
}) }
onMounted(() => perform(refresh))
</script>
