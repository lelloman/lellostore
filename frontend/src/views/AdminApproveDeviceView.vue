<template>
  <DefaultLayout>
    <div class="d-flex align-center justify-space-between mb-6">
      <div>
        <h1 class="page-title">Approve a device</h1>
        <p>Enter the code a device such as a TV box shows while it sets itself up. Approving gives it its own device token.</p>
      </div>
      <v-btn variant="text" :to="{ name: 'device-tokens-admin' }">Device tokens</v-btn>
    </div>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" class="mb-4" data-test="notice">{{ notice }}</v-alert>
    <v-card class="pa-5 mb-6">
      <v-form @submit.prevent="lookup">
        <v-text-field
          v-model="code"
          label="Code shown on the device"
          :disabled="busy"
          hint="For example WDJB-MJHT. Letters only, case does not matter."
          persistent-hint
          autocomplete="off"
        />
        <v-btn type="submit" color="primary" class="mt-4" :disabled="busy || !canLookup">Look up</v-btn>
      </v-form>
    </v-card>
    <v-card v-if="enrollment" class="pa-5" data-test="enrollment">
      <h2 class="text-h6 mb-3">Request {{ enrollment.user_code }}</h2>
      <v-table density="compact" class="mb-4">
        <tbody>
          <tr><th>Device name</th><td>{{ enrollment.requested_name }}</td></tr>
          <tr><th>Model</th><td>{{ enrollment.model ?? 'Not reported' }}</td></tr>
          <tr><th>Android SDK</th><td>{{ enrollment.android_sdk ?? 'Not reported' }}</td></tr>
          <tr><th>LelloStore version</th><td>{{ enrollment.client_version ?? 'Not reported' }}</td></tr>
          <tr><th>Address</th><td>{{ enrollment.source_ip }}</td></tr>
          <tr><th>Requested</th><td>{{ duration(enrollment.age_s) }} ago</td></tr>
          <tr><th>Code expires in</th><td>{{ duration(enrollment.expires_in) }}</td></tr>
        </tbody>
      </v-table>
      <p class="mb-4">Approve only if this matches the device in front of you: anyone holding it gets the access you choose here.</p>
      <v-radio-group v-model="mode" inline :disabled="busy" label="Approve as">
        <v-radio label="New device" value="new" />
        <v-radio label="Replace a device" value="replace" :disabled="!tokens.length" />
      </v-radio-group>
      <template v-if="mode === 'new'">
        <v-text-field v-model="name" label="Token name" :disabled="busy" />
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
      </template>
      <template v-else>
        <v-select
          v-model="replaceId"
          label="Device token to replace"
          :items="replaceOptions"
          item-title="title"
          item-value="value"
          :disabled="busy"
          hint="The token keeps its groups and grants; its old secret stops working"
          persistent-hint
        />
        <v-text-field v-model="name" label="New name (optional)" :disabled="busy" class="mt-2" />
      </template>
      <div class="d-flex flex-wrap align-center mt-4" style="gap: 8px">
        <v-btn color="primary" :disabled="busy || !canApprove" @click="approve">Approve</v-btn>
        <v-btn color="error" variant="tonal" :disabled="busy" @click="deny">Deny</v-btn>
        <v-checkbox v-model="blockSource" label="When denying, refuse new requests from this address for 24 hours" density="compact" hide-details :disabled="busy" />
      </div>
    </v-card>
  </DefaultLayout>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { api, request, type AppGroup } from '@/services/api'
type Enrollment = {
  user_code: string; requested_name: string; model: string | null; android_sdk: number | null; client_version: string | null
  source_ip: string; created_at: string; expires_at: string; age_s: number; expires_in: number
}
type Token = { id: string; name: string; subject: string; groups: { id: number; name: string }[]; last_used_at: string | null; revoked_at: string | null }
const enrollmentBase = '/api/admin/device-enrollments'
const route = useRoute()
const code = ref(typeof route.query.code === 'string' ? route.query.code : '')
const enrollment = ref<Enrollment | null>(null)
const groups = ref<AppGroup[]>([]), tokens = ref<Token[]>([])
const mode = ref<'new' | 'replace'>('new')
const name = ref(''), replaceId = ref<string | null>(null), blockSource = ref(true)
const groupIds = ref<number[]>([])
const busy = ref(false), error = ref(''), notice = ref('')
const normalized = computed(() => code.value.replace(/[\s-]/g, '').toUpperCase())
const canLookup = computed(() => normalized.value.length === 8)
const canApprove = computed(() => mode.value === 'new' ? !!name.value.trim() : !!replaceId.value)
const groupOptions = computed(() => groups.value.map(group => ({ title: group.name, value: group.id })))
// Every token can be replaced, revoked ones included: rekeying reactivates it.
const replaceOptions = computed(() => tokens.value.map(token => ({
  title: `${token.name}${token.revoked_at ? ' (revoked)' : ''} · ${token.groups.map(g => g.name).join(', ') || 'no groups'}`,
  value: token.id,
})))
function displayCode() { return `${normalized.value.slice(0, 4)}-${normalized.value.slice(4)}` }
function duration(seconds: number) {
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.floor(seconds / 60), rest = seconds % 60
  return rest ? `${minutes} min ${rest} s` : `${minutes} min`
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : 'Request failed' } finally { busy.value = false }
}
async function lookup() { if (!canLookup.value) return; await perform(async () => {
  enrollment.value = null
  const [found, groupResponse, tokenList] = await Promise.all([
    request<Enrollment>(`${enrollmentBase}/${encodeURIComponent(displayCode())}`),
    api.getAppGroups(),
    request<Token[]>('/api/admin/device-tokens'),
  ])
  groups.value = groupResponse.groups; tokens.value = tokenList
  enrollment.value = found
  name.value = found.requested_name; mode.value = 'new'; replaceId.value = null
  // Default to the system "all" group, which grants every app.
  groupIds.value = groups.value.filter(group => group.system_kind === 'all').map(group => group.id)
}) }
async function approve() { if (!enrollment.value || !canApprove.value) return; await perform(async () => {
  const body: { name?: string; group_ids?: number[]; replace_device_token_id?: string } = {}
  if (mode.value === 'new') { body.name = name.value.trim(); body.group_ids = [...groupIds.value] } else {
    body.replace_device_token_id = replaceId.value!
    if (name.value.trim()) body.name = name.value.trim()
  }
  const approved = await request<{ device_token: Token; rekeyed: boolean }>(`${enrollmentBase}/${encodeURIComponent(enrollment.value!.user_code)}/approve`, { method: 'POST', body: JSON.stringify(body) })
  enrollment.value = null; code.value = ''
  notice.value = approved.rekeyed
    ? `Approved: “${approved.device_token.name}” was rekeyed. The device finishes setting up within a few seconds.`
    : `Approved: “${approved.device_token.name}” was created. The device finishes setting up within a few seconds.`
}) }
async function deny() { if (!enrollment.value) return; await perform(async () => {
  await request(`${enrollmentBase}/${encodeURIComponent(enrollment.value!.user_code)}/deny?block_source=${blockSource.value}`, { method: 'POST' })
  enrollment.value = null; code.value = ''
  notice.value = 'Request denied'
}) }
onMounted(() => { if (canLookup.value) void lookup() })
</script>
