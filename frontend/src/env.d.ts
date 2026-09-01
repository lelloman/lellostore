/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_ISSUER_URL: string
  readonly VITE_OIDC_CLIENT_ID: string
  readonly VITE_OIDC_ADMIN_ROLE: string
  readonly VITE_OIDC_ROLE_CLAIM_PATH: string
  readonly VITE_API_BASE_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
