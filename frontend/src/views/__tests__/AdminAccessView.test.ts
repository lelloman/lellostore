import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import AdminAccessView from '../AdminAccessView.vue'
import { api } from '@/services/api'

vi.mock('@/services/api', () => ({
  api: {
    getAdminUsers: vi.fn(),
    getAppGroups: vi.fn(),
    getAdminApps: vi.fn(),
    getUserAccess: vi.fn(),
    createAppGroup: vi.fn(),
    renameAppGroup: vi.fn(),
    deleteAppGroup: vi.fn(),
    setDirectGrant: vi.fn(),
    removeDirectGrant: vi.fn(),
    setGroupGrant: vi.fn(),
    removeGroupGrant: vi.fn(),
    addGroupMember: vi.fn(),
    removeGroupMember: vi.fn(),
  },
}))

const user = {
  subject: 'user-1',
  email: 'user@example.com',
  first_seen_at: '2026-09-02',
  last_seen_at: '2026-09-02',
}

function mountView() {
  return shallowMount(AdminAccessView, {
    global: {
      stubs: {
        DefaultLayout: { template: '<main><slot /></main>' },
        VWindow: { template: '<div><slot /></div>' },
        VWindowItem: { template: '<section><slot /></section>' },
        VCard: { template: '<article><slot /></article>' },
        VTabs: { template: '<nav><slot /></nav>' },
        VTab: { template: '<button><slot /></button>' },
        VAlert: { template: '<div><slot /></div>' },
        VTextField: {
          name: 'VTextField',
          props: ['modelValue'],
          template: '<input :value="modelValue" />',
        },
      },
    },
  })
}

describe('AdminAccessView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getAdminUsers).mockResolvedValue({ users: [user] })
    vi.mocked(api.getAppGroups).mockResolvedValue({ groups: [] })
    vi.mocked(api.getAdminApps).mockResolvedValue({ apps: [] })
    vi.mocked(api.getUserAccess).mockResolvedValue({
      user,
      direct_grants: [],
      groups: [],
      effective_access: [],
    })
    vi.mocked(api.createAppGroup).mockResolvedValue({
      id: 1,
      name: 'Media apps',
      created_at: '2026-09-02',
      updated_at: '2026-09-02',
    })
  })

  it('loads users, groups, the complete admin catalogue, and selected-user access', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.getAdminUsers).toHaveBeenCalledOnce()
    expect(api.getAppGroups).toHaveBeenCalledOnce()
    expect(api.getAdminApps).toHaveBeenCalledOnce()
    expect(api.getUserAccess).toHaveBeenCalledWith('user-1')
    const userSelect = wrapper.findAllComponents({ name: 'VSelect' })[0]
    expect(userSelect.props('items')).toEqual([
      { title: 'user@example.com · user-1', value: 'user-1' },
    ])
    expect(wrapper.text()).toContain('Removing a direct grant does not remove access')
  })

  it('shows a deliberate failure state when administration data cannot load', async () => {
    vi.mocked(api.getAdminUsers).mockRejectedValueOnce(new Error('Access service unavailable'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('Access service unavailable')
  })
})
