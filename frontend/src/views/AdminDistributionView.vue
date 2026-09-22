<template>
  <v-container>
    <div class="d-flex align-center mb-4">
      <h1>Paravoid distribution</h1>
      <v-spacer />
      <v-btn :loading="loading" @click="refresh">Refresh</v-btn>
    </div>
    <v-alert type="info" class="mb-4">App authors pin these public keys in their signed shell APKs. Empty shells require a verified bootstrap VPK before publication. Embedded-shell publication is waiting for Paravoid packaging integration.</v-alert>
    <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
    <v-card v-if="configuration && !configuration.configured" title="Signing keys not configured">
      <v-card-text>An operator must configure separate head and grant signing keys using PARAVOID_SIGNING_CONFIG, then restart the server. Release signing keys stay with the app author.</v-card-text>
    </v-card>
    <template v-if="configuration?.signing">
      <v-card title="Update endpoint" class="mb-4">
        <v-card-text>{{ configuration.signing.base_url }}</v-card-text>
      </v-card>
      <v-card v-for="role in roles" :key="role.name" :title="role.name" class="mb-4">
        <v-card-text>
          <p>{{ role.description }}</p>
          <div v-for="(fingerprint, id) in role.fingerprints" :key="id" class="mt-4">
            <strong>{{ id }}</strong>
            <v-chip v-if="id === role.active" size="small" class="ml-2">Active</v-chip>
            <p class="fingerprint mt-1">SHA-256: {{ fingerprint }}</p>
          </div>
        </v-card-text>
      </v-card>
      <v-alert type="info" class="mb-4">Export includes public head/grant keys only. The author must add their application ID, release public keys and version floors to the shell trust policy. New trust keys require a shell APK update; changing the server configuration does not update installed shells.</v-alert>
      <v-btn color="primary" @click="download">Export public keys</v-btn>
    </template>
  </v-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type ParavoidConfiguration } from '@/services/api'
const configuration = ref<ParavoidConfiguration | null>(null)
const loading = ref(false)
const error = ref('')
const roles = computed(() => {
  const signing = configuration.value?.signing
  return signing ? [
    { name: 'Head signing keys', description: 'Authenticate update availability and freshness.', active: signing.active_head_key, fingerprints: signing.head_fingerprints },
    { name: 'Grant signing keys', description: 'Authenticate credentials issued with personalized installers.', active: signing.active_grant_key, fingerprints: signing.grant_fingerprints },
  ] : []
})
async function refresh() {
  loading.value = true
  error.value = ''
  try { configuration.value = await api.getParavoidConfiguration() }
  catch (e) { error.value = e instanceof Error ? e.message : 'Could not load distribution configuration' }
  finally { loading.value = false }
}
function download() {
  const signing = configuration.value?.signing
  if (!signing) return
  const bytes = JSON.stringify({ headKeys: signing.head_keys, grantKeys: signing.grant_keys }, null, 2)
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'paravoid-online-public-keys.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
onMounted(refresh)
</script>

<style scoped>
.fingerprint { overflow-wrap: anywhere; font-family: monospace; }
</style>
