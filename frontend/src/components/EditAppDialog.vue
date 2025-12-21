<template>
  <v-dialog v-model="model" max-width="500">
    <v-card>
      <v-card-title>Edit Application</v-card-title>

      <v-card-text>
        <v-form ref="form" v-model="isValid">
          <v-text-field
            v-model="name"
            label="App Name"
            :rules="[rules.required]"
            class="mb-4"
          />

          <v-textarea
            v-model="description"
            label="Description"
            rows="3"
            hint="Optional description for the app"
            persistent-hint
          />
        </v-form>

        <v-alert v-if="error" type="error" variant="tonal" class="mt-4">
          {{ error }}
        </v-alert>
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isSaving" @click="close">
          Cancel
        </v-btn>
        <v-btn
          color="primary"
          :disabled="!isValid || !hasChanges"
          :loading="isSaving"
          @click="save"
        >
          Save
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useAppsStore } from '@/stores/apps'
import type { App } from '@/services/api'

const props = defineProps<{
  app: App | null
}>()

const model = defineModel<boolean>()
const emit = defineEmits<{
  saved: [void]
}>()

const appsStore = useAppsStore()

const form = ref()
const name = ref('')
const description = ref('')
const isValid = ref(false)
const isSaving = ref(false)
const error = ref<string | null>(null)

const rules = {
  required: (v: string) => !!v?.trim() || 'Name is required',
}

const hasChanges = computed(() => {
  if (!props.app) return false
  return name.value !== props.app.name ||
         description.value !== (props.app.description || '')
})

async function save() {
  if (!props.app || !isValid.value) return

  isSaving.value = true
  error.value = null

  try {
    await appsStore.updateApp(props.app.package_name, {
      name: name.value,
      description: description.value || undefined,
    })
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to save'
  } finally {
    isSaving.value = false
  }
}

function close() {
  model.value = false
}

function reset() {
  if (props.app) {
    name.value = props.app.name
    description.value = props.app.description || ''
  }
  error.value = null
}

// Reset when dialog opens or app changes
watch([model, () => props.app], ([open]) => {
  if (open) reset()
})
</script>
