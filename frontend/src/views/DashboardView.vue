<template>
  <DefaultLayout>
    <h1 class="text-h4 mb-4">Dashboard</h1>
    <p class="text-body-1 mb-6">Welcome to the lellostore admin dashboard.</p>

    <v-alert type="info" variant="tonal" class="mb-4">
      <v-alert-title>Ready for Epic 6</v-alert-title>
      The frontend foundation is complete. App management features will be implemented in Epic 6.
    </v-alert>

    <v-row>
      <v-col cols="12" md="4">
        <v-card>
          <v-card-title>
            <v-icon start>mdi-package-variant</v-icon>
            Apps
          </v-card-title>
          <v-card-text>
            Manage your application catalog, upload new apps, and update metadata.
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12" md="4">
        <v-card>
          <v-card-title>
            <v-icon start>mdi-upload</v-icon>
            Upload
          </v-card-title>
          <v-card-text>
            Upload new APK or AAB files to add applications to the catalog.
          </v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12" md="4">
        <v-card>
          <v-card-title>
            <v-icon start>mdi-history</v-icon>
            Versions
          </v-card-title>
          <v-card-text>
            Manage application versions, track updates, and remove old releases.
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </DefaultLayout>
</template>

<script setup lang="ts">
import DefaultLayout from '@/layouts/DefaultLayout.vue'
</script>
