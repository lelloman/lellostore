import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppsListView from '../AppsListView.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import AuthenticatedImg from '@/components/AuthenticatedImg.vue'
import { useAuthStore } from '@/stores/auth'
import { api } from '@/services/api'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/services/api', () => ({
  api: {
    getApps: vi.fn(),
    getIconUrl: vi.fn(),
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

describe('AppsListView authorization', () => {
  beforeEach(() => {
    vi.mocked(api.getApps).mockResolvedValue({ apps: [] })
  })

  it('hides upload controls from non-admin users', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const authStore = useAuthStore()
    authStore.setUser({
      expired: false,
      profile: { sub: 'regular-user', realm_access: { roles: ['user'] } },
    } as unknown as Parameters<typeof authStore.setUser>[0])

    const wrapper = shallowMount(AppsListView, {
      global: {
        plugins: [pinia],
        stubs: {
          DefaultLayout: { template: '<main><slot /></main>' },
          VBtn: { template: '<button><slot /></button>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Upload App')
    expect(wrapper.findComponent(UploadDialog).exists()).toBe(false)
  })

  it('keeps the catalog compact and lets users switch to a table', async () => {
    vi.mocked(api.getApps).mockResolvedValueOnce({
      apps: [{
        package_name: 'com.example.app',
        name: 'Example App',
        description: 'An example application',
        icon_url: '/api/apps/com.example.app/icon',
        latest_version: {
          version_code: 7,
          version_name: '1.7.0',
          size: 1024,
          min_sdk: 24,
          uploaded_at: '2026-09-01T12:00:00Z',
        },
      }],
    })
    vi.mocked(api.getIconUrl).mockReturnValue('/api/apps/com.example.app/icon')
    const pinia = createPinia()
    setActivePinia(pinia)

    const wrapper = shallowMount(AppsListView, {
      global: {
        plugins: [pinia],
        stubs: {
          DefaultLayout: { template: '<main><slot /></main>' },
          VRow: { template: '<div><slot /></div>' },
          VCol: { template: '<div><slot /></div>' },
          VCard: { template: '<section><slot /></section>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Private distribution')
    expect(wrapper.text()).not.toContain('Browse the Android apps')
    expect(wrapper.text()).not.toContain('App catalog')
    const icon = wrapper.findComponent(AuthenticatedImg)
    expect(icon.attributes('width')).toBe('100%')
    expect(icon.attributes('height')).toBe('100%')

    const toggle = wrapper.findComponent({ name: 'VBtnToggle' })
    expect(toggle.exists()).toBe(true)
    expect(toggle.props('modelValue')).toBe('cards')
    toggle.vm.$emit('update:modelValue', 'table')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'VDataTable' }).exists()).toBe(true)
  })
})
