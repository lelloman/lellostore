<template>
  <v-dialog v-model="model" max-width="400">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon :color="confirmColor" class="mr-2">mdi-alert-circle</v-icon>
        {{ title }}
      </v-card-title>

      <v-card-text>
        {{ message }}
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isLoading" @click="cancel">
          {{ cancelText }}
        </v-btn>
        <v-btn
          :color="confirmColor"
          :loading="isLoading"
          @click="confirm"
        >
          {{ confirmText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  confirmColor?: string
}>(), {
  confirmText: 'Confirm',
  cancelText: 'Cancel',
  confirmColor: 'primary',
})

const model = defineModel<boolean>()
const emit = defineEmits<{
  confirm: [void]
  cancel: [void]
}>()

const isLoading = ref(false)

async function confirm() {
  isLoading.value = true
  try {
    emit('confirm')
  } finally {
    isLoading.value = false
    model.value = false
  }
}

function cancel() {
  emit('cancel')
  model.value = false
}
</script>
