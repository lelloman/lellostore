<template>
  <v-img
    v-if="blobUrl"
    :src="blobUrl"
    v-bind="$attrs"
  />
  <slot v-else-if="isLoading" name="loading">
    <v-progress-circular indeterminate size="24" />
  </slot>
  <slot v-else name="fallback" />
</template>

<script setup lang="ts">
import { useAuthenticatedImage } from '@/composables/useAuthenticatedImage'

const props = defineProps<{
  src: string | undefined
}>()

const { blobUrl, isLoading } = useAuthenticatedImage(() => props.src)
</script>
