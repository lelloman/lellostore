<template>
  <v-btn variant="text" prepend-icon="mdi-qrcode-scan" @click="open">Connect your phone</v-btn>
  <v-dialog v-model="visible" max-width="440">
    <v-card title="Connect your phone">
      <v-card-text>
        <p class="mb-4">Open the Android app and choose Scan QR code on the server setup screen.</p>
        <v-alert v-if="error" type="error">{{ error }}</v-alert>
        <div v-else class="text-center">
          <img v-if="qr" :src="qr" alt="QR code for connecting to this store" width="280" height="280">
          <v-progress-circular v-else indeterminate />
          <p class="text-body-2 mt-3">{{ serverUrl }}</p>
          <p class="text-caption mt-2">You will sign in on your phone after confirming the store.</p>
        </div>
      </v-card-text>
      <v-card-actions><v-spacer /><v-btn @click="visible = false">Close</v-btn></v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import QRCode from 'qrcode'

const visible = ref(false)
const qr = ref('')
const error = ref('')
const serverUrl = window.location.origin

async function open() {
  visible.value = true
  error.value = ''
  if (!serverUrl.startsWith('https://')) {
    error.value = 'Open this store at its public HTTPS address to connect your phone.'
    return
  }
  try {
    qr.value = await QRCode.toDataURL(JSON.stringify({ type: 'store-setup', version: 1, server_url: serverUrl }), {
      width: 280, margin: 4, errorCorrectionLevel: 'M',
    })
  } catch {
    error.value = 'Could not create the connection code. You can enter the server address on your phone instead.'
  }
}
</script>
