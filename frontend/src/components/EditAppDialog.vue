<template>
  <v-dialog v-model="model" max-width="500">
    <v-card>
      <v-card-title>Edit Application</v-card-title>

      <v-card-text>
        <v-form ref="form" v-model="isValid">
          <!-- Icon upload section -->
          <div class="d-flex align-center mb-4">
            <v-avatar size="72" class="mr-4" color="grey-lighten-2">
              <v-img
                v-if="iconPreview || iconBlobUrl"
                :src="iconPreview ?? iconBlobUrl ?? undefined"
              />
              <v-icon v-else icon="mdi-android" size="36" />
            </v-avatar>
            <div>
              <v-btn
                variant="outlined"
                size="small"
                :loading="isUploadingIcon"
                @click="triggerIconUpload"
              >
                <v-icon start icon="mdi-upload" />
                Change Icon
              </v-btn>
              <input
                ref="iconInput"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                style="display: none"
                @change="handleIconSelect"
              />
              <div class="text-caption text-medium-emphasis mt-1">
                Square image (PNG, JPG, WebP)
              </div>
            </div>
          </div>

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
import { api } from '@/services/api'
import type { App } from '@/services/api'
import { useToast } from '@/composables/useToast'
import { useAuthenticatedImage } from '@/composables/useAuthenticatedImage'

const props = defineProps<{
  app: App | null
}>()

const model = defineModel<boolean>()
const emit = defineEmits<{
  saved: [void]
}>()

const appsStore = useAppsStore()
const toast = useToast()

// Fetch icon with auth header
const { blobUrl: iconBlobUrl, refresh: refreshIcon } = useAuthenticatedImage(
  () => props.app ? api.getIconUrl(props.app.package_name) : undefined
)

const form = ref()
const iconInput = ref<HTMLInputElement>()
const name = ref('')
const description = ref('')
const isValid = ref(false)
const isSaving = ref(false)
const isUploadingIcon = ref(false)
const error = ref<string | null>(null)
const iconPreview = ref<string | null>(null)

const rules = {
  required: (v: string) => !!v?.trim() || 'Name is required',
}

const hasChanges = computed(() => {
  if (!props.app) return false
  return name.value !== props.app.name ||
         description.value !== (props.app.description || '')
})

function triggerIconUpload() {
  iconInput.value?.click()
}

async function handleIconSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !props.app) return

  // Validate file type
  if (!file.type.startsWith('image/')) {
    error.value = 'Please select an image file'
    return
  }

  // Validate file size (5MB max)
  if (file.size > 5 * 1024 * 1024) {
    error.value = 'Image must be less than 5MB'
    return
  }

  // Show preview
  iconPreview.value = URL.createObjectURL(file)

  // Upload immediately
  isUploadingIcon.value = true
  error.value = null

  try {
    await api.uploadIcon(props.app.package_name, file)
    toast.success('Icon updated successfully')
    // Refresh the authenticated icon
    iconPreview.value = null
    await refreshIcon()
    emit('saved')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to upload icon'
    iconPreview.value = null
  } finally {
    isUploadingIcon.value = false
    // Reset input so same file can be selected again
    input.value = ''
  }
}

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
  iconPreview.value = null
}

// Reset when dialog opens or app changes
watch([model, () => props.app], ([open]) => {
  if (open) reset()
})
</script>
