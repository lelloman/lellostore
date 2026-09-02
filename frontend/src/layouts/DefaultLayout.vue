<template>
  <v-app class="app-shell">
    <v-app-bar class="app-header" flat height="72">
      <v-container class="shell-container d-flex align-center h-100">
        <button class="brand" type="button" @click="router.push({ name: 'apps' })">
          <span class="brand-mark" aria-hidden="true">
            <v-icon size="22">mdi-package-variant-closed</v-icon>
          </span>
          <span class="brand-name">LelloStore</span>
        </button>

        <v-btn
          class="catalog-link d-none d-sm-flex ml-6"
          :to="{ name: 'apps' }"
          variant="text"
          prepend-icon="mdi-view-grid-outline"
        >
          Catalog
        </v-btn>

        <v-spacer />

        <v-chip
          v-if="authStore.isAdmin"
          class="d-none d-md-flex mr-2"
          color="primary"
          size="small"
          variant="tonal"
        >
          Administrator
        </v-chip>

        <v-btn
          icon
          variant="text"
          :title="isDark ? 'Use light theme' : 'Use dark theme'"
          :aria-label="isDark ? 'Use light theme' : 'Use dark theme'"
          @click="toggleTheme"
        >
          <v-icon>{{ isDark ? 'mdi-weather-sunny' : 'mdi-weather-night' }}</v-icon>
        </v-btn>

        <v-menu v-if="authStore.isAuthenticated" location="bottom end" offset="10">
          <template #activator="{ props }">
            <v-btn
              class="account-button ml-1"
              icon
              variant="text"
              aria-label="Open account menu"
              v-bind="props"
            >
              <v-avatar color="primary" size="34">
                <span class="account-initials">{{ userInitials }}</span>
              </v-avatar>
            </v-btn>
          </template>

          <v-list class="account-menu" min-width="260" rounded="xl">
            <v-list-item class="py-3">
              <v-list-item-title class="font-weight-bold">
                {{ userDisplayName }}
              </v-list-item-title>
              <v-list-item-subtitle>
                {{ authStore.isAdmin ? 'Administrator account' : 'Catalog member' }}
              </v-list-item-subtitle>
            </v-list-item>
            <v-divider />
            <v-list-item prepend-icon="mdi-logout" title="Sign out" @click="handleLogout" />
          </v-list>
        </v-menu>
      </v-container>
    </v-app-bar>

    <v-main>
      <v-container class="shell-container page-container">
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useTheme } from 'vuetify'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const authStore = useAuthStore()
const router = useRouter()
const theme = useTheme()

const savedTheme = localStorage.getItem('lellostore-theme')
if (savedTheme === 'light' || savedTheme === 'dark') {
  theme.global.name.value = savedTheme
}

const isDark = computed(() => theme.global.current.value.dark)

const userDisplayName = computed(() => {
  const profile = authStore.userProfile
  if (!profile) return 'LelloStore user'
  return profile.name ?? profile.preferred_username ?? profile.email ?? profile.sub ?? 'LelloStore user'
})

const userInitials = computed(() => {
  const words = userDisplayName.value.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return 'LS'
  return words.slice(0, 2).map(word => word[0]).join('').toUpperCase()
})

function toggleTheme() {
  const nextTheme = isDark.value ? 'light' : 'dark'
  theme.global.name.value = nextTheme
  localStorage.setItem('lellostore-theme', nextTheme)
}

async function handleLogout() {
  await authStore.logout()
  router.push({ name: 'login' })
}
</script>

<style scoped>
.app-shell {
  background:
    radial-gradient(circle at 85% 8%, rgba(var(--v-theme-primary), 0.08), transparent 24rem),
    rgb(var(--v-theme-background));
}

.app-header {
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.08) !important;
  background: rgba(var(--v-theme-surface), 0.88) !important;
  backdrop-filter: blur(18px);
}

.shell-container {
  width: min(100%, 1240px);
  max-width: 1240px;
}

.page-container {
  padding-top: clamp(2rem, 5vw, 4.5rem);
  padding-bottom: 5rem;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.7rem;
  padding: 0;
  border: 0;
  color: rgb(var(--v-theme-on-surface));
  background: transparent;
  cursor: pointer;
}

.brand-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 12px;
  color: rgb(var(--v-theme-on-primary));
  background: linear-gradient(145deg, rgb(var(--v-theme-primary)), #2f985a);
  box-shadow: 0 8px 20px rgba(23, 107, 58, 0.25);
}

.brand-name {
  font-size: 1.12rem;
  font-weight: 780;
  letter-spacing: -0.035em;
}

.catalog-link {
  color: rgb(var(--v-theme-secondary));
}

.account-initials {
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.account-menu {
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}

@media (max-width: 600px) {
  .page-container {
    padding-inline: 1rem;
  }
}
</style>
