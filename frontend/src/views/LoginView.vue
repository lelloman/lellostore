<template>
  <v-app>
    <v-main>
      <v-container class="fill-height" fluid>
        <v-row align="center" justify="center">
          <v-col cols="12" sm="8" md="4">
            <v-card class="elevation-12">
              <v-toolbar color="primary" dark flat>
                <v-toolbar-title>lellostore</v-toolbar-title>
              </v-toolbar>

              <v-card-text class="text-center py-8">
                <v-icon size="64" color="primary" class="mb-4">
                  mdi-package-variant-closed
                </v-icon>
                <p class="text-h6 mb-4">Private App Distribution</p>
                <p class="text-body-2 text-medium-emphasis mb-6">
                  Sign in to access the admin dashboard
                </p>

                <v-alert
                  v-if="authStore.error"
                  type="error"
                  variant="tonal"
                  class="mb-4"
                >
                  {{ authStore.error }}
                </v-alert>

                <v-btn
                  color="primary"
                  size="large"
                  block
                  :loading="isLoggingIn"
                  @click="handleLogin"
                >
                  <v-icon start>mdi-login</v-icon>
                  Sign in with SSO
                </v-btn>
              </v-card-text>
            </v-card>
          </v-col>
        </v-row>
      </v-container>
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const isLoggingIn = ref(false)

async function handleLogin() {
  isLoggingIn.value = true
  await authStore.login()
  // Note: This redirects to OIDC provider, so loading state
  // stays until redirect happens
}
</script>
