import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppDetailView from '../AppDetailView.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EditAppDialog from '@/components/EditAppDialog.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useAppsStore } from '@/stores/apps'
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
    handleLogoutCallback: vi.fn(),
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

  it('lets regular users download versions without exposing delete actions', async () => {
    vi.mocked(api.getApp).mockResolvedValueOnce({
      package_name: 'com.example.app',
      name: 'Example App',
      icon_url: '/api/apps/com.example.app/icon',
      versions: [{
        version_code: 7,
        version_name: '1.7.0',
        apk_url: '/api/apps/com.example.app/versions/7/apk',
        size: 1024,
        sha256: 'hash',
        min_sdk: 24,
        uploaded_at: '2026-09-01T12:00:00Z',
      }, {
        version_code: 6,
        version_name: '1.6.0',
        apk_url: '/api/apps/com.example.app/versions/6/apk',
        size: 2048,
        sha256: 'older-hash',
        min_sdk: 24,
        uploaded_at: '2026-08-01T12:00:00Z',
      }],
    })
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
          VDataTable: { template: '<div><slot name="item.actions" :item="$attrs.items[0]" /></div>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('[title="Download APK"]').exists()).toBe(true)
    expect(wrapper.find('[title="Delete version"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="detail-latest-size"]').text()).toBe('1.0 KB latest')
    expect(wrapper.find('[data-testid="detail-total-size"]').text()).toBe('3.0 KB total')
  })

  it('does not render a cached app when the requested app fails to load', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const appsStore = useAppsStore()
    appsStore.currentApp = {
      package_name: 'com.stale.app',
      name: 'Stale App',
      icon_url: '/api/apps/com.stale.app/icon',
      versions: [],
    }
    vi.mocked(api.getApp).mockRejectedValueOnce(new Error('Not found'))

    const wrapper = shallowMount(AppDetailView, {
      global: {
        plugins: [pinia],
        stubs: {
          DefaultLayout: { template: '<main><slot /></main>' },
          VBtn: { template: '<button><slot /></button>' },
          VAlert: { template: '<aside><slot /></aside>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Stale App')
    expect(wrapper.text()).toContain('The requested application could not be found.')
  })
})
