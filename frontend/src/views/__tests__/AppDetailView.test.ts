import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppDetailView from '../AppDetailView.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EditAppDialog from '@/components/EditAppDialog.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { api } from '@/services/api'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { packageName: 'com.example.app' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

vi.mock('@/services/api', () => ({
  api: {
    getApp: vi.fn(),
    getIconUrl: vi.fn(),
    downloadApk: vi.fn(),
  },
}))

vi.mock('@/services/auth', () => ({
  authService: {
    getUser: vi.fn(),
    login: vi.fn(),
    handleCallback: vi.fn(),
    logout: vi.fn(),
    silentRenew: vi.fn(),
  },
}))

describe('AppDetailView authorization', () => {
  beforeEach(() => {
    vi.mocked(api.getApp).mockResolvedValue({
      package_name: 'com.example.app',
      name: 'Example App',
      icon_url: '/api/apps/com.example.app/icon',
      versions: [],
    })
  })

  it('hides all mutation controls from non-admin users', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const authStore = useAuthStore()
    authStore.setUser({
      expired: false,
      profile: { sub: 'regular-user', realm_access: { roles: ['user'] } },
    } as unknown as Parameters<typeof authStore.setUser>[0])

    const wrapper = shallowMount(AppDetailView, {
      global: {
        plugins: [pinia],
        stubs: {
          DefaultLayout: { template: '<main><slot /></main>' },
          VBtn: { template: '<button><slot /></button>' },
          VCard: { template: '<section><slot /></section>' },
          VCardTitle: { template: '<header><slot /></header>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Edit')
    expect(wrapper.text()).not.toContain('Upload Version')
    expect(wrapper.findComponent(EditAppDialog).exists()).toBe(false)
    expect(wrapper.findComponent(UploadDialog).exists()).toBe(false)
    expect(wrapper.findAllComponents(ConfirmDialog)).toHaveLength(0)
  })
})
