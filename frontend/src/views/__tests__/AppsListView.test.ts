import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppsListView from '../AppsListView.vue'
import UploadDialog from '@/components/UploadDialog.vue'
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
})
