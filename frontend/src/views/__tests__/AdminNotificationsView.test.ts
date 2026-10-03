import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import AdminNotificationsView from '../AdminNotificationsView.vue'
import { request } from '@/services/api'

vi.mock('@/services/api', () => ({ request: vi.fn() }))

function overview() {
  return {
    connections: 2, outcomes: [],
    senders: [{
      id: 'talia', name: 'Talìa', enabled: true,
      applications: [{ package: 'com.lelloman.talia', certificates: ['a'.repeat(64)] }],
      manifest: { types: [] }, overrides: [], max_pending: 100, max_bytes: 4096,
      rate: 1, burst: 10, pending: 3, queued_bytes: 256,
    }],
  }
}

function mountView() {
  return shallowMount(AdminNotificationsView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        DefaultLayout: { template: '<main><slot /></main>' },
        VDialog: { props: ['modelValue'], template: '<section v-if="modelValue" role="dialog"><slot /></section>' },
        VBtn: { props: ['disabled', 'type'], template: '<button :disabled="disabled" :type="type"><slot /></button>' },
        VForm: { template: '<form><slot /></form>' },
        VTextField: {
          props: ['modelValue', 'label'], emits: ['update:modelValue'],
          template: '<input :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('AdminNotificationsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(request).mockResolvedValue(overview())
  })

  it('lists registered apps without opening invitation or policy forms', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('com.lelloman.talia')
    expect(wrapper.text()).toContain('Talìa')
    expect(wrapper.text()).toContain('Enabled')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Administrator overrides')
  })

  it('creates an invitation with normalized certificate without inventing a registration', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === 'Create invitation')!.trigger('click')
    await wrapper.find('input[aria-label="App / backend name"]').setValue(' New app ')
    await wrapper.find('input[aria-label="Android package"]').setValue(' com.example.new ')
    await wrapper.find('input[aria-label="Signing certificate SHA-256"]').setValue('A'.repeat(64))
    vi.mocked(request).mockResolvedValueOnce({ invitation: 'private-invitation' })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith('/api/admin/notifications/invitations', {
      method: 'POST', body: JSON.stringify({ name: 'New app', applications: [{ package: 'com.example.new', certificates: ['a'.repeat(64)] }] }),
    })
    expect(wrapper.text()).toContain('Invitation created')
    expect(wrapper.text()).toContain('after registration')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  })

  it('discards unsaved registration changes when management closes', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('button[aria-label="Manage com.lelloman.talia"]').trigger('click')
    await wrapper.find('input[aria-label="Package"]').setValue('com.example.unsaved')
    await wrapper.find('button[aria-label="Close registration"]').trigger('click')
    expect(wrapper.text()).toContain('com.lelloman.talia')
    expect(wrapper.text()).not.toContain('com.example.unsaved')
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('shows registration guidance when no apps are registered', async () => {
    vi.mocked(request).mockResolvedValueOnce({ connections: 0, outcomes: [], senders: [] })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('No apps registered yet')
    expect(wrapper.text()).toContain('share it privately')
  })
})
