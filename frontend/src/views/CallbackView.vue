<template>
  <v-app>
    <v-main>
      <v-container class="fill-height" fluid>
        <v-row align="center" justify="center">
          <v-col cols="12" class="text-center">
            <template v-if="error">
              <v-icon size="64" color="error" class="mb-4">
                mdi-alert-circle
              </v-icon>
              <p class="text-h6 mb-2">Authentication Failed</p>
              <p class="text-body-2 text-medium-emphasis mb-6">
                {{ error }}
              </p>
              <v-btn color="primary" @click="goToLogin">
                Try Again
              </v-btn>
            </template>

            <template v-else>
              <v-progress-circular
                indeterminate
                size="64"
                color="primary"
                class="mb-4"
              />
              <p class="text-h6">Completing sign in...</p>
            </template>
          </v-col>
        </v-row>
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    await authStore.handleCallback()

    // Redirect to saved path or dashboard
    const redirectPath = sessionStorage.getItem('redirectPath') || '/'
    sessionStorage.removeItem('redirectPath')

    router.replace(redirectPath)
  } catch (e) {
    console.error('Callback error:', e)
    error.value = e instanceof Error ? e.message : 'Authentication failed'
  }
})

function goToLogin() {
  router.push({ name: 'login' })
}
</script>
