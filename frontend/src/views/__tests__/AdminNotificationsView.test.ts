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
      keys: [{ key: 'B'.repeat(87), revoked: false }], registrations: 2, max_pending: 100, max_bytes: 4096,
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
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(request).mockResolvedValue(overview()) })
  it('lists approved servers and registrations', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.text()).toContain('Talìa'); expect(wrapper.text()).toContain('Enabled')
    expect(wrapper.text()).toContain('2 registrations'); expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
  it('approves a public key without sender credentials or app identity', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('input[aria-label="Server name"]').setValue(' New server ')
    await wrapper.find('input[aria-label="VAPID public key"]').setValue('B'.repeat(87))
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/notifications/senders', { method: 'POST', body: JSON.stringify({ name: 'New server', key: 'B'.repeat(87) }) })
    expect(wrapper.text()).toContain('Server approved')
  })
  it('discards unsaved changes', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('button[aria-label="Manage Talìa"]').trigger('click')
    const inputs = wrapper.findAll('input[aria-label="Server name"]')
    await inputs[inputs.length - 1]!.setValue('Unsaved')
    await wrapper.find('button[aria-label="Close server"]').trigger('click')
    expect(wrapper.text()).toContain('Talìa'); expect(wrapper.text()).not.toContain('Unsaved')
    expect(request).toHaveBeenCalledTimes(1)
  })
  it('requires confirmation before revoking an approved key', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('button[aria-label="Manage Talìa"]').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === 'Revoke key')!.trigger('click')
    expect(request).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(b => b.text() === 'Confirm revocation')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith(`/api/admin/notifications/senders/talia/keys/${'B'.repeat(87)}`, { method: 'DELETE' })
  })
  it('shows backend errors', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('Unavailable'))
    const wrapper = mountView(); await flushPromises(); expect(wrapper.text()).toContain('Unavailable')
  })
})
