<template>
  <v-app class="login-shell">
    <v-main>
      <div class="login-glow login-glow--top" />
      <div class="login-glow login-glow--bottom" />

      <v-container class="login-container fill-height">
        <v-card class="login-card surface-panel" width="100%">
          <v-row no-gutters>
            <v-col cols="12" md="6" class="brand-panel">
              <div class="brand-mark mb-10">
                <v-icon size="28">mdi-package-variant-closed</v-icon>
              </div>

              <p class="page-kicker mb-3">Private Android distribution</p>
              <h1 class="brand-title mb-5">Apps, delivered with confidence.</h1>
              <p class="brand-copy mb-10">
                One secure catalog for your organization’s Android applications and every
                approved release.
              </p>

              <div class="feature-list">
                <div class="feature-item">
                  <v-icon size="21">mdi-shield-lock-outline</v-icon>
                  <span>Protected by your organization account</span>
                </div>
                <div class="feature-item">
                  <v-icon size="21">mdi-check-decagram-outline</v-icon>
                  <span>Curated and verified application releases</span>
                </div>
                <div class="feature-item">
                  <v-icon size="21">mdi-update</v-icon>
                  <span>Release history and updates in one place</span>
                </div>
              </div>
            </v-col>

            <v-col cols="12" md="6" class="signin-panel">
              <div class="signin-content">
                <p class="page-kicker mb-3">Welcome back</p>
                <h2 class="signin-title mb-3">Open your app catalog</h2>
                <p class="text-body-1 text-medium-emphasis mb-8">
                  Sign in to browse the applications and releases available to your account.
                </p>

                <v-alert
                  v-if="authStore.error"
                  type="error"
                  variant="tonal"
                  class="mb-5"
                  rounded="lg"
                >
                  {{ authStore.error }}
                </v-alert>

                <v-btn
                  color="primary"
                  size="x-large"
                  block
                  :loading="isLoggingIn"
                  prepend-icon="mdi-login"
                  @click="handleLogin"
                >
                  Sign in with SSO
                </v-btn>

                <p class="signin-note mt-5 mb-0">
                  You’ll continue through your organization’s secure identity provider.
                </p>
              </div>
            </v-col>
          </v-row>
        </v-card>
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
  // The redirect to the OIDC provider keeps the loading state active.
}
</script>

<style scoped>
.login-shell {
  background:
    linear-gradient(135deg, rgba(var(--v-theme-primary), 0.08), transparent 42%),
    rgb(var(--v-theme-background));
}

.login-container {
  position: relative;
  z-index: 1;
  max-width: 1100px;
  padding: 32px;
}

.login-card {
  overflow: hidden;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  box-shadow: 0 30px 80px rgba(14, 38, 23, 0.12) !important;
}

.brand-panel {
  padding: 64px;
  background:
    radial-gradient(circle at 90% 10%, rgba(255, 255, 255, 0.13), transparent 30%),
    rgb(var(--v-theme-primary));
  color: rgb(var(--v-theme-on-primary));
}

.brand-panel .page-kicker {
  color: rgba(var(--v-theme-on-primary), 0.7);
}

.brand-mark {
  display: grid;
  width: 52px;
  height: 52px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.13);
}

.brand-title {
  max-width: 440px;
  font-size: clamp(2.35rem, 4vw, 3.5rem);
  font-weight: 750;
  line-height: 1.04;
  letter-spacing: -0.045em;
}

.brand-copy {
  max-width: 430px;
  color: rgba(var(--v-theme-on-primary), 0.76);
  font-size: 1.05rem;
  line-height: 1.7;
}

.feature-list {
  display: grid;
  gap: 17px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 13px;
  color: rgba(var(--v-theme-on-primary), 0.88);
  font-size: 0.92rem;
}

.feature-item :deep(.v-icon) {
  color: rgb(var(--v-theme-on-primary)) !important;
}

.signin-panel {
  display: grid;
  min-height: 560px;
  place-items: center;
  padding: 64px;
}

.signin-content {
  width: 100%;
  max-width: 390px;
}

.signin-title {
  font-size: clamp(1.9rem, 3vw, 2.5rem);
  font-weight: 720;
  line-height: 1.15;
  letter-spacing: -0.035em;
}

.signin-note {
  color: rgb(var(--v-theme-on-surface-variant));
  font-size: 0.78rem;
  line-height: 1.55;
  text-align: center;
}

.login-glow {
  position: fixed;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(2px);
}

.login-glow--top {
  top: -220px;
  right: -180px;
  width: 520px;
  height: 520px;
  background: rgba(var(--v-theme-primary), 0.08);
}

.login-glow--bottom {
  bottom: -250px;
  left: -170px;
  width: 470px;
  height: 470px;
  border: 1px solid rgba(var(--v-theme-primary), 0.12);
}

@media (max-width: 959px) {
  .login-container {
    max-width: 680px;
  }

  .brand-panel,
  .signin-panel {
    padding: 44px;
  }

  .signin-panel {
    min-height: auto;
  }

  .feature-list {
    display: none;
  }

  .brand-copy {
    margin-bottom: 0 !important;
  }
}

@media (max-width: 599px) {
  .login-container {
    align-items: start;
    padding: 16px;
  }

  .brand-panel,
  .signin-panel {
    padding: 32px 24px;
  }

  .brand-mark {
    margin-bottom: 28px !important;
  }

  .brand-title {
    font-size: 2.25rem;
  }
}
</style>
