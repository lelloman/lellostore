<template>
  <v-app>
    <v-app-bar color="primary">
      <v-app-bar-nav-icon @click="drawer = !drawer" />

      <v-toolbar-title>lellostore</v-toolbar-title>

      <v-spacer />

      <!-- Theme toggle -->
      <v-btn icon @click="toggleTheme" :title="isDark ? 'Light mode' : 'Dark mode'">
        <v-icon>{{ isDark ? 'mdi-weather-sunny' : 'mdi-weather-night' }}</v-icon>
      </v-btn>

      <!-- User menu -->
      <v-menu v-if="authStore.isAuthenticated">
        <template #activator="{ props }">
          <v-btn icon v-bind="props">
            <v-icon>mdi-account-circle</v-icon>
          </v-btn>
        </template>
        <v-list>
          <v-list-item>
            <v-list-item-title>{{ userDisplayName }}</v-list-item-title>
            <v-list-item-subtitle v-if="authStore.isAdmin">
              Administrator
            </v-list-item-subtitle>
          </v-list-item>
          <v-divider />
          <v-list-item @click="handleLogout">
            <template #prepend>
              <v-icon>mdi-logout</v-icon>
            </template>
            <v-list-item-title>Logout</v-list-item-title>
          </v-list-item>
        </v-list>
      </v-menu>
    </v-app-bar>

    <v-navigation-drawer v-model="drawer" temporary>
      <v-list nav>
        <v-list-item
          prepend-icon="mdi-view-dashboard"
          title="Dashboard"
          to="/"
        />
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <v-container fluid>
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useTheme } from 'vuetify'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const authStore = useAuthStore()
const router = useRouter()
const theme = useTheme()

const drawer = ref(false)

const isDark = computed(() => theme.global.current.value.dark)

const userDisplayName = computed(() => {
  const profile = authStore.userProfile
  if (!profile) return 'User'
  return profile.email ?? profile.preferred_username ?? profile.name ?? profile.sub ?? 'User'
})

function toggleTheme() {
  theme.global.name.value = isDark.value ? 'light' : 'dark'
}

async function handleLogout() {
  await authStore.logout()
  router.push({ name: 'login' })
}
</script>
