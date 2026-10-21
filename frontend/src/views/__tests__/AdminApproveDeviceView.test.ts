import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import AdminApproveDeviceView from '../AdminApproveDeviceView.vue'
import { api, request } from '@/services/api'

vi.mock('@/services/api', () => ({ request: vi.fn(), api: { getAppGroups: vi.fn() } }))
const query: Record<string, string> = {}
vi.mock('vue-router', () => ({ useRoute: () => ({ query }) }))

function enrollment() {
  return {
    user_code: 'WDJB-MJHT', requested_name: 'Mi Box (living room)', model: 'MIBOX4', android_sdk: 28, client_version: '1.0.0',
    source_ip: '198.51.100.7', created_at: '2026-10-21 10:00:00', expires_at: '2026-10-21 10:10:00', age_s: 75, expires_in: 525,
  }
}
function tokens() {
  return [
    { id: 'tok-1', name: 'Old box', subject: 'device-token:tok-1', groups: [{ id: 1, name: 'all' }], last_used_at: null, revoked_at: '2026-10-02 10:00:00' },
  ]
}
function groups() {
  return [
    { id: 1, name: 'all', system_kind: 'all' as const, created_at: '', updated_at: '', grants: [], user_subjects: [] },
    { id: 2, name: 'Media apps', system_kind: null, created_at: '', updated_at: '', grants: [], user_subjects: [] },
  ]
}

function mountView() {
  return shallowMount(AdminApproveDeviceView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        DefaultLayout: { template: '<main><slot /></main>' },
        VAlert: { template: '<div role="alert"><slot /></div>' },
        VBtn: { props: ['disabled', 'type', 'to'], template: '<button :disabled="disabled" :type="type"><slot /></button>' },
        VForm: { template: '<form><slot /></form>' },
        VTextField: {
          props: ['modelValue', 'label'], emits: ['update:modelValue'],
          template: '<input :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        VCheckbox: {
          props: ['modelValue', 'label'], emits: ['update:modelValue'],
          template: '<input type="checkbox" :aria-label="label" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
        },
        VRadioGroup: { name: 'VRadioGroup', props: ['modelValue'], emits: ['update:modelValue'], template: '<div><slot /></div>' },
        VSelect: { name: 'VSelect', props: ['modelValue', 'label', 'items'], emits: ['update:modelValue'], template: '<select :aria-label="label" />' },
      },
    },
  })
}

function respond() {
  vi.mocked(request).mockImplementation(async (path: string) => {
    if (path === '/api/admin/device-tokens') return tokens()
    if (path.startsWith('/api/admin/device-enrollments/WDJB-MJHT') && !path.includes('/approve') && !path.includes('/deny')) return enrollment()
    if (path.endsWith('/approve')) return { device_token: { ...tokens()[0], name: 'Mi Box (living room)' }, rekeyed: false }
    return undefined
  })
}

describe('AdminApproveDeviceView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    for (const key of Object.keys(query)) delete query[key]
    vi.mocked(api.getAppGroups).mockResolvedValue({ groups: groups() })
    respond()
  })

  it('looks a typed code up and shows what is being approved', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(request).not.toHaveBeenCalled()
    await wrapper.find('input[aria-label="Code shown on the device"]').setValue('wdjb mjht')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-enrollments/WDJB-MJHT')
    const text = wrapper.find('[data-test="enrollment"]').text()
    expect(text).toContain('Mi Box (living room)'); expect(text).toContain('MIBOX4'); expect(text).toContain('28')
    expect(text).toContain('198.51.100.7'); expect(text).toContain('1 min 15 s ago'); expect(text).toContain('8 min 45 s')
    expect((wrapper.find('input[aria-label="Token name"]').element as HTMLInputElement).value).toBe('Mi Box (living room)')
    // The system "all" group is preselected.
    expect(wrapper.findComponent({ name: 'VSelect' }).props('modelValue')).toEqual([1])
  })

  it('does not look up an incomplete code', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('input[aria-label="Code shown on the device"]').setValue('WDJB-MJ')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).not.toHaveBeenCalled()
  })

  it('looks a deep-linked code up at once and approves a new device', async () => {
    query.code = 'WDJB-MJHT'
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.find('[data-test="enrollment"]').exists()).toBe(true)
    wrapper.findComponent({ name: 'VSelect' }).vm.$emit('update:modelValue', [1, 2])
    await wrapper.find('input[aria-label="Token name"]').setValue(' Living room ')
    await wrapper.findAll('button').find(b => b.text() === 'Approve')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-enrollments/WDJB-MJHT/approve', {
      method: 'POST', body: JSON.stringify({ name: 'Living room', group_ids: [1, 2] }),
    })
    expect(wrapper.find('[data-test="notice"]').text()).toContain('was created')
    expect(wrapper.find('[data-test="enrollment"]').exists()).toBe(false)
  })

  it('approves as a replacement for an existing token', async () => {
    query.code = 'WDJB-MJHT'
    const wrapper = mountView(); await flushPromises()
    wrapper.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', 'replace'); await flushPromises()
    const select = wrapper.findComponent({ name: 'VSelect' })
    expect(select.props('items')).toEqual([{ title: 'Old box (revoked) · all', value: 'tok-1' }])
    expect(wrapper.findAll('button').find(b => b.text() === 'Approve')!.attributes('disabled')).toBeDefined()
    select.vm.$emit('update:modelValue', 'tok-1'); await flushPromises()
    await wrapper.find('input[aria-label="New name (optional)"]').setValue('')
    await wrapper.findAll('button').find(b => b.text() === 'Approve')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-enrollments/WDJB-MJHT/approve', {
      method: 'POST', body: JSON.stringify({ replace_device_token_id: 'tok-1' }),
    })
  })

  it('denies, blocking the address unless unchecked', async () => {
    query.code = 'WDJB-MJHT'
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('input[type="checkbox"]').setValue(false)
    await wrapper.findAll('button').find(b => b.text() === 'Deny')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-enrollments/WDJB-MJHT/deny?block_source=false', { method: 'POST' })
    expect(wrapper.text()).toContain('Request denied')
  })

  it('shows unknown codes as errors', async () => {
    query.code = 'BBBB-BBBB'
    vi.mocked(request).mockRejectedValueOnce(new Error('Not found: Unknown or expired code'))
    const wrapper = mountView(); await flushPromises()
    expect(wrapper.text()).toContain('Unknown or expired code')
    expect(wrapper.find('[data-test="enrollment"]').exists()).toBe(false)
  })
})
