<template>
  <div class="toast-container">
    <v-snackbar
      v-for="toast in toasts"
      :key="toast.id"
      :model-value="true"
      :color="toast.type"
      :timeout="toast.timeout"
      location="bottom right"
      @update:model-value="remove(toast.id)"
    >
      {{ toast.message }}
      <template #actions>
        <v-btn variant="text" @click="remove(toast.id)">
          Close
        </v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts, remove } = useToast()
</script>
