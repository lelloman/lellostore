<template>
  <v-dialog v-model="model" max-width="500" persistent>
    <v-card>
      <v-card-title>Upload Application</v-card-title>

      <v-card-text>
        <!-- Drop zone -->
        <div
          class="drop-zone pa-8 text-center rounded-lg mb-4"
          :class="{ 'drop-zone--active': isDragOver, 'drop-zone--has-file': selectedFile }"
          @dragover.prevent="isDragOver = true"
          @dragleave="isDragOver = false"
          @drop.prevent="onDrop"
          @click="openFilePicker"
        >
          <input
            ref="fileInput"
            type="file"
            accept=".apk,.aab"
            hidden
            @change="onFileSelected"
          />

          <template v-if="selectedFile">
            <v-icon size="48" color="success" class="mb-2">mdi-check-circle</v-icon>
            <p class="text-body-1 font-weight-medium">{{ selectedFile.name }}</p>
            <p class="text-body-2 text-medium-emphasis">{{ formatSize(selectedFile.size) }}</p>
            <v-btn variant="text" size="small" @click.stop="clearFile">Change file</v-btn>
          </template>

          <template v-else>
            <v-icon size="48" color="grey" class="mb-2">mdi-cloud-upload</v-icon>
            <p class="text-body-1">Drag and drop APK or AAB file here</p>
            <p class="text-body-2 text-medium-emphasis">or click to browse</p>
          </template>
        </div>

        <!-- Optional fields -->
        <v-text-field
          v-model="appName"
          label="App Name (optional)"
          hint="Override the name extracted from the APK"
          persistent-hint
          class="mb-4"
        />

        <v-textarea
          v-model="description"
          label="Description (optional)"
          rows="3"
        />

        <!-- Upload progress -->
        <v-progress-linear
          v-if="isUploading"
          indeterminate
          color="primary"
          class="mt-4"
        />

        <!-- Error -->
        <v-alert v-if="error" type="error" variant="tonal" class="mt-4">
          {{ error }}
        </v-alert>
      </v-card-text>

      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="isUploading" @click="close">
          Cancel
        </v-btn>
        <v-btn
          color="primary"
          :disabled="!selectedFile || isUploading"
          :loading="isUploading"
          @click="upload"
        >
          Upload
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAppsStore } from '@/stores/apps'

const model = defineModel<boolean>()
const emit = defineEmits<{
  uploaded: [void]
}>()

const appsStore = useAppsStore()
const fileInput = ref<HTMLInputElement>()

const selectedFile = ref<File | null>(null)
const appName = ref('')
const description = ref('')
const isDragOver = ref(false)
const isUploading = ref(false)
const error = ref<string | null>(null)

const ALLOWED_EXTENSIONS = ['.apk', '.aab']

function isValidFile(file: File): boolean {
  const extension = '.' + file.name.split('.').pop()?.toLowerCase()
  return ALLOWED_EXTENSIONS.includes(extension)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function openFilePicker() {
  fileInput.value?.click()
}

function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) selectFile(file)
}

function onDrop(event: DragEvent) {
  isDragOver.value = false
  const file = event.dataTransfer?.files[0]
  if (file) selectFile(file)
}

function selectFile(file: File) {
  if (!isValidFile(file)) {
    error.value = 'Please select an APK or AAB file'
    return
  }
  selectedFile.value = file
  error.value = null
}

function clearFile() {
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function upload() {
  if (!selectedFile.value) return

  isUploading.value = true
  error.value = null

  try {
    await appsStore.uploadApp(
      selectedFile.value,
      appName.value || undefined,
      description.value || undefined
    )
    emit('uploaded')
    close()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Upload failed'
  } finally {
    isUploading.value = false
  }
}

function close() {
  model.value = false
}

function reset() {
  selectedFile.value = null
  appName.value = ''
  description.value = ''
  error.value = null
  isDragOver.value = false
  if (fileInput.value) fileInput.value.value = ''
}

// Reset when dialog closes
watch(model, (open) => {
  if (!open) reset()
})
</script>

<style scoped>
.drop-zone {
  border: 2px dashed rgba(var(--v-border-color), var(--v-border-opacity));
  cursor: pointer;
  transition: all 0.2s;
}

.drop-zone:hover,
.drop-zone--active {
  border-color: rgb(var(--v-theme-primary));
  background-color: rgba(var(--v-theme-primary), 0.05);
}

.drop-zone--has-file {
  border-color: rgb(var(--v-theme-success));
  border-style: solid;
}
</style>
