<template>
  <DefaultLayout>
    <div class="access-heading mb-7">
      <div>
        <p class="page-kicker mb-2">Administration</p>
        <h1 class="page-title">App access</h1>
        <p class="text-medium-emphasis mt-3 mb-0">
          Direct grants and live group rules combine to produce each user’s access.
        </p>
      </div>
      <v-btn
        icon="mdi-refresh"
        variant="text"
        aria-label="Refresh access data"
        :loading="loading"
        @click="loadAll"
      />
    </div>

    <v-alert v-if="error" class="mb-6" type="error" variant="tonal" closable @click:close="error = ''">
      {{ error }}
    </v-alert>

    <v-skeleton-loader v-if="loading && !loaded" class="surface-panel" type="heading, table-row@6" />

    <v-card v-else class="surface-panel access-panel">
      <v-tabs v-model="tab" color="primary">
        <v-tab value="users">Users</v-tab>
        <v-tab value="groups">Groups</v-tab>
      </v-tabs>
      <v-divider />

      <v-window v-model="tab">
        <v-window-item value="users" class="panel-content">
          <v-alert v-if="users.length === 0" type="info" variant="tonal">
            No users have authenticated yet. Users appear here after their first successful Store request.
          </v-alert>
          <template v-else>
            <v-select
              v-model="selectedSubject"
              class="user-select"
              label="User"
              :items="userOptions"
              item-title="title"
              item-value="value"
              variant="outlined"
              hide-details
              @update:model-value="loadSelectedUser"
            />

            <v-skeleton-loader v-if="userLoading" type="table-row@4" />
            <template v-else-if="selectedAccess">
              <div class="membership-section">
                <h2 class="text-subtitle-1 font-weight-bold">Group membership</h2>
                <p class="text-body-2 text-medium-emphasis">
                  Group rules are live and affect every member on their next refresh.
                </p>
                <div v-if="groups.length" class="chip-list">
                  <v-checkbox-btn
                    v-for="group in groups"
                    :key="group.id"
                    :model-value="group.user_subjects.includes(selectedSubject)"
                    :label="group.name"
                    :aria-label="`Membership in ${group.name}`"
                    @update:model-value="setMembership(group, $event)"
                  />
                </div>
                <p v-else class="text-body-2 text-medium-emphasis">No groups have been created.</p>
              </div>

              <v-data-table
                class="grant-table"
                :headers="userHeaders"
                :items="userGrantRows"
                item-key="packageName"
                :items-per-page="-1"
              >
                <template #item.app="{ item }">
                  <strong>{{ item.name }}</strong>
                  <div class="text-caption text-medium-emphasis">{{ item.packageName }}</div>
                </template>
                <template #item.direct="{ item }">
                  <v-select
                    :model-value="item.direct"
                    :items="grantChoices"
                    density="compact"
                    variant="outlined"
                    hide-details
                    :aria-label="`Direct grant for ${item.name}`"
                    @update:model-value="setDirectGrant(item.packageName, $event)"
                  />
                </template>
                <template #item.groups="{ item }">
                  <span v-if="item.groupLevel">{{ levelLabel(item.groupLevel) }}</span>
                  <span v-else class="text-medium-emphasis">None</span>
                </template>
                <template #item.effective="{ item }">
                  <v-chip v-if="item.effective" color="primary" size="small" variant="tonal">
                    {{ levelLabel(item.effective) }}
                  </v-chip>
                  <span v-else class="text-medium-emphasis">No access</span>
                </template>
              </v-data-table>
              <p class="removal-note text-body-2 text-medium-emphasis">
                Removing a direct grant does not remove access that is still supplied by a group.
              </p>
            </template>
          </template>
        </v-window-item>

        <v-window-item value="groups" class="panel-content">
          <form class="create-group" @submit.prevent="createGroup">
            <v-text-field
              v-model="newGroupName"
              label="New group name"
              variant="outlined"
              maxlength="100"
              hide-details
            />
            <v-btn type="submit" color="primary" prepend-icon="mdi-plus" :disabled="!newGroupName.trim()">
              Create group
            </v-btn>
          </form>

          <v-alert v-if="groups.length === 0" type="info" variant="tonal">
            No app groups yet. Create one to assign the same live app rules to multiple users.
          </v-alert>

          <div v-else class="group-list">
            <v-card v-for="group in groups" :key="group.id" class="group-card" variant="outlined">
              <div class="group-toolbar">
                <v-text-field
                  v-model="groupNames[group.id]"
                  label="Group name"
                  variant="outlined"
                  density="compact"
                  hide-details
                  maxlength="100"
                />
                <v-btn variant="text" @click="renameGroup(group)">Save name</v-btn>
                <v-btn color="error" variant="text" @click="deleteGroup(group)">Delete</v-btn>
              </div>

              <div class="group-grid">
                <section>
                  <h3 class="text-subtitle-2 mb-3">App rules</h3>
                  <div v-for="app in apps" :key="app.package_name" class="rule-row">
                    <span>
                      <strong>{{ app.name }}</strong>
                      <small>{{ app.package_name }}</small>
                    </span>
                    <v-select
                      :model-value="groupGrant(group, app.package_name)"
                      :items="grantChoices"
                      density="compact"
                      variant="outlined"
                      hide-details
                      :aria-label="`${group.name} access to ${app.name}`"
                      @update:model-value="setGroupGrant(group, app.package_name, $event)"
                    />
                  </div>
                  <p v-if="apps.length === 0" class="text-body-2 text-medium-emphasis">No apps published.</p>
                </section>
                <section>
                  <h3 class="text-subtitle-2 mb-3">Members</h3>
                  <v-checkbox-btn
                    v-for="user in users"
                    :key="user.subject"
                    :model-value="group.user_subjects.includes(user.subject)"
                    :label="user.email || user.subject"
                    @update:model-value="setGroupMembership(group, user.subject, $event)"
                  />
                  <p v-if="users.length === 0" class="text-body-2 text-medium-emphasis">No known users.</p>
                </section>
              </div>
            </v-card>
          </div>
        </v-window-item>
      </v-window>
    </v-card>
  </DefaultLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import {
  api,
  type AccessLevel,
  type AppGroup,
  type AppListItem,
  type KnownUser,
  type UserAccess,
} from '@/services/api'

type GrantChoice = AccessLevel | null

const tab = ref<'users' | 'groups'>('users')
const loading = ref(false)
const userLoading = ref(false)
const loaded = ref(false)
const error = ref('')
const users = ref<KnownUser[]>([])
const apps = ref<AppListItem[]>([])
const groups = ref<AppGroup[]>([])
const selectedSubject = ref('')
const selectedAccess = ref<UserAccess | null>(null)
const newGroupName = ref('')
const groupNames = reactive<Record<number, string>>({})

const grantChoices = [
  { title: 'None', value: null },
  { title: 'Stable', value: 'stable' },
  { title: 'Beta + stable', value: 'beta' },
]

const userHeaders = [
  { title: 'Application', key: 'app' },
  { title: 'Direct grant', key: 'direct', sortable: false, width: '190px' },
  { title: 'Via groups', key: 'groups', sortable: false },
  { title: 'Effective', key: 'effective', sortable: false },
]

const userOptions = computed(() => users.value.map(user => ({
  title: user.email ? `${user.email} · ${user.subject}` : user.subject,
  value: user.subject,
})))

function higherLevel(levels: Array<AccessLevel | undefined>): AccessLevel | null {
  if (levels.includes('beta')) return 'beta'
  if (levels.includes('stable')) return 'stable'
  return null
}

const userGrantRows = computed(() => apps.value.map(app => {
  const direct = selectedAccess.value?.direct_grants.find(grant => grant.package_name === app.package_name)?.access_level ?? null
  const memberGroups = groups.value.filter(group => group.user_subjects.includes(selectedSubject.value))
  const groupLevel = higherLevel(memberGroups.map(group => group.grants.find(grant => grant.package_name === app.package_name)?.access_level))
  const effective = selectedAccess.value?.effective_access.find(grant => grant.package_name === app.package_name)?.access_level ?? null
  return { packageName: app.package_name, name: app.name, direct, groupLevel, effective }
}))

function levelLabel(level: AccessLevel) {
  return level === 'beta' ? 'Beta + stable' : 'Stable'
}

function reportFailure(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : 'The access change could not be saved.'
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [userResponse, groupResponse, appResponse] = await Promise.all([
      api.getAdminUsers(), api.getAppGroups(), api.getAdminApps(),
    ])
    users.value = userResponse.users
    groups.value = groupResponse.groups
    apps.value = appResponse.apps
    for (const group of groups.value) groupNames[group.id] = group.name
    if (!selectedSubject.value && users.value.length) selectedSubject.value = users.value[0].subject
    if (selectedSubject.value) await loadSelectedUser()
    loaded.value = true
  } catch (cause) {
    reportFailure(cause)
  } finally {
    loading.value = false
  }
}

async function loadSelectedUser() {
  if (!selectedSubject.value) return
  userLoading.value = true
  try {
    selectedAccess.value = await api.getUserAccess(selectedSubject.value)
  } catch (cause) {
    reportFailure(cause)
  } finally {
    userLoading.value = false
  }
}

async function reloadGroupsAndUser() {
  groups.value = (await api.getAppGroups()).groups
  for (const group of groups.value) groupNames[group.id] = group.name
  await loadSelectedUser()
}

async function setDirectGrant(packageName: string, level: GrantChoice) {
  try {
    if (level) await api.setDirectGrant(selectedSubject.value, packageName, level)
    else await api.removeDirectGrant(selectedSubject.value, packageName)
    await loadSelectedUser()
  } catch (cause) { reportFailure(cause) }
}

async function setMembership(group: AppGroup, present: boolean | null) {
  await setGroupMembership(group, selectedSubject.value, Boolean(present))
}

async function setGroupMembership(group: AppGroup, subject: string, present: boolean | null) {
  try {
    if (present) await api.addGroupMember(group.id, subject)
    else await api.removeGroupMember(group.id, subject)
    await reloadGroupsAndUser()
  } catch (cause) { reportFailure(cause) }
}

async function createGroup() {
  const name = newGroupName.value.trim()
  if (!name) return
  try {
    await api.createAppGroup(name)
    newGroupName.value = ''
    await reloadGroupsAndUser()
  } catch (cause) { reportFailure(cause) }
}

async function renameGroup(group: AppGroup) {
  try {
    await api.renameAppGroup(group.id, groupNames[group.id])
    await reloadGroupsAndUser()
  } catch (cause) { reportFailure(cause) }
}

async function deleteGroup(group: AppGroup) {
  if (!window.confirm(`Delete “${group.name}”? Access supplied only by this group will be removed.`)) return
  try {
    await api.deleteAppGroup(group.id)
    await reloadGroupsAndUser()
  } catch (cause) { reportFailure(cause) }
}

function groupGrant(group: AppGroup, packageName: string): AccessLevel | null {
  return group.grants.find(grant => grant.package_name === packageName)?.access_level ?? null
}

async function setGroupGrant(group: AppGroup, packageName: string, level: GrantChoice) {
  try {
    if (level) await api.setGroupGrant(group.id, packageName, level)
    else await api.removeGroupGrant(group.id, packageName)
    await reloadGroupsAndUser()
  } catch (cause) { reportFailure(cause) }
}

onMounted(loadAll)
</script>

<style scoped>
.access-heading, .group-toolbar, .create-group { display: flex; align-items: center; gap: 1rem; }
.access-heading { justify-content: space-between; }
.access-panel { overflow: hidden; }
.panel-content { padding: clamp(1rem, 3vw, 2rem); }
.user-select { max-width: 580px; margin-bottom: 2rem; }
.membership-section { padding: 1.25rem; margin-bottom: 1.5rem; border-radius: 14px; background: rgba(var(--v-theme-primary), .055); }
.chip-list { display: flex; flex-wrap: wrap; gap: .5rem 1.5rem; }
.removal-note { margin: 1rem 0 0; }
.create-group { max-width: 680px; margin-bottom: 2rem; }
.create-group :deep(.v-input) { flex: 1; }
.group-list { display: grid; gap: 1rem; }
.group-card { padding: 1.25rem; }
.group-toolbar :deep(.v-input) { flex: 1; }
.group-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr); gap: 2rem; margin-top: 1.5rem; }
.rule-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px; align-items: center; gap: 1rem; padding: .6rem 0; }
.rule-row small { display: block; color: rgb(var(--v-theme-on-surface-variant)); }
@media (max-width: 760px) {
  .group-grid { grid-template-columns: 1fr; }
  .group-toolbar, .create-group { align-items: stretch; flex-direction: column; }
  .rule-row { grid-template-columns: 1fr; }
}
</style>
