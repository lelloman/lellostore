<template>
  <DefaultLayout>
    <div class="distribution-heading mb-7">
      <div>
        <p class="page-kicker mb-2">Administration</p>
        <h1 class="page-title">Distribution</h1>
        <p class="text-medium-emphasis mt-3 mb-0">
          Public signing keys and update settings for Paravoid apps.
        </p>
      </div>
      <v-btn icon="mdi-refresh" variant="text" title="Refresh distribution settings" aria-label="Refresh distribution settings" :loading="loading" @click="refresh" />
    </div>

    <v-alert v-if="error" type="error" variant="tonal" class="mb-6" closable @click:close="error = ''">{{ error }}</v-alert>
    <v-skeleton-loader v-if="loading && !configuration" class="surface-panel" type="heading, paragraph, table-row-divider@4" />

    <v-card v-else-if="configuration && !configuration.configured" class="empty-state surface-panel text-center">
      <span class="empty-icon mb-5"><v-icon size="42">mdi-key-outline</v-icon></span>
      <h2 class="text-h5 font-weight-bold mb-2">Signing keys not configured</h2>
      <p class="text-body-1 text-medium-emphasis mb-0">
        An operator needs to configure the server’s head and grant signing keys before they can be exported.
      </p>
      <p class="text-body-2 text-medium-emphasis mt-3 mb-0">
        Set <code>PARAVOID_SIGNING_CONFIG</code> and restart the server. App authors keep their own release signing keys.
      </p>
    </v-card>

    <template v-else-if="configuration?.signing">
      <v-card class="surface-panel endpoint-panel mb-6">
        <div class="endpoint-heading">
          <div>
            <h2 class="text-subtitle-1 font-weight-bold">Update endpoint</h2>
            <p class="text-body-2 text-medium-emphasis mt-1">The address Paravoid apps use to check for updates.</p>
          </div>
          <v-btn color="primary" prepend-icon="mdi-download-outline" @click="download">Export public keys</v-btn>
        </div>
        <p class="endpoint-url mt-4 mb-0">{{ configuration.signing.base_url }}</p>
      </v-card>

      <div class="key-grid mb-6">
        <v-card v-for="role in roles" :key="role.name" class="surface-panel key-panel">
          <div class="key-heading">
            <span class="key-icon" aria-hidden="true"><v-icon color="primary" :icon="role.icon" /></span>
            <div>
              <h2 class="text-subtitle-1 font-weight-bold">{{ role.name }}</h2>
              <p class="text-body-2 text-medium-emphasis mt-1">{{ role.description }}</p>
            </div>
          </div>
          <v-divider />
          <ul class="key-list">
            <li v-for="(fingerprint, id) in role.fingerprints" :key="id" class="key-row">
              <div class="key-label">
                <strong class="key-id">{{ id }}</strong>
                <v-chip v-if="id === role.active" color="primary" size="small" variant="tonal" prepend-icon="mdi-check-circle-outline">Active</v-chip>
              </div>
              <p class="text-caption text-medium-emphasis mt-3 mb-1">SHA-256 fingerprint</p>
              <p class="fingerprint mb-0">{{ fingerprint }}</p>
            </li>
          </ul>
          <p v-if="!Object.keys(role.fingerprints).length" class="text-body-2 text-medium-emphasis pa-5">No public keys available.</p>
        </v-card>
      </div>

      <v-card class="surface-panel guidance-panel">
        <h2 class="text-subtitle-1 font-weight-bold mb-3">Using these keys</h2>
        <p class="text-body-2 text-medium-emphasis mb-3">
          The export contains public head and grant keys. App authors pin them in their signed shell APKs,
          alongside the application ID, release public keys and minimum allowed versions.
        </p>
        <p class="text-body-2 text-medium-emphasis mb-0">
          Adding new trusted keys requires a shell APK update. Changing the server configuration alone does not update installed apps.
        </p>
        <v-divider class="my-4" />
        <h3 class="text-subtitle-2 mb-2">Publication requirements</h3>
        <p class="text-body-2 text-medium-emphasis mb-0">
          Empty shells need a verified bootstrap VPK before publication.
          Embedded-shell publication is awaiting Paravoid packaging integration.
        </p>
      </v-card>
    </template>
  </DefaultLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { api, type ParavoidConfiguration } from '@/services/api'
const configuration = ref<ParavoidConfiguration | null>(null)
const loading = ref(false)
const error = ref('')
const roles = computed(() => {
  const signing = configuration.value?.signing
  return signing ? [
    { name: 'Head signing keys', icon: 'mdi-update', description: 'Authenticate update availability and freshness.', active: signing.active_head_key, fingerprints: signing.head_fingerprints },
    { name: 'Grant signing keys', icon: 'mdi-key-outline', description: 'Authenticate credentials issued with personalized installers.', active: signing.active_grant_key, fingerprints: signing.grant_fingerprints },
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
.distribution-heading, .endpoint-heading, .key-heading, .key-label {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.distribution-heading, .endpoint-heading, .key-label { justify-content: space-between; }
.distribution-heading > div, .key-heading > div { min-width: 0; }
.distribution-heading > .v-btn { flex-shrink: 0; }
.endpoint-panel, .guidance-panel { padding: clamp(1rem, 3vw, 1.5rem); }
.endpoint-heading { flex-wrap: wrap; }
.endpoint-url, .fingerprint, .key-id, code {
  overflow-wrap: anywhere;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.endpoint-url {
  padding: 1rem;
  border-radius: 14px;
  color: rgb(var(--v-theme-primary-readable));
  background: rgba(var(--v-theme-primary), 0.055);
  font-size: 0.9rem;
}
.key-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1.5rem; }
.key-panel { overflow: hidden; }
.key-heading, .key-row { padding: 1.5rem; }
.key-heading { align-items: flex-start; }
.key-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 14px;
  background: rgba(var(--v-theme-primary), 0.08);
}
.key-list { list-style: none; padding: 0; }
.key-row + .key-row { border-top: 1px solid rgba(var(--v-theme-on-surface), 0.08); }
.key-label { flex-wrap: wrap; }
.key-id { font-size: 0.875rem; }
.fingerprint { font-size: 0.8rem; line-height: 1.7; color: rgb(var(--v-theme-on-surface-variant)); }
.empty-state { display: flex; min-height: 360px; align-items: center; flex-direction: column; justify-content: center; padding: 3rem 1.5rem; }
.empty-state p { max-width: 600px; }
.empty-icon {
  display: grid;
  width: 88px;
  height: 88px;
  place-items: center;
  border-radius: 28px;
  color: rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.1);
}
@media (max-width: 760px) {
  .key-grid { grid-template-columns: 1fr; }
  .key-heading, .key-row { padding: 1rem; }
}
</style>
