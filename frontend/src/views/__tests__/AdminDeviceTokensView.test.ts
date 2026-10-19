import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import AdminDeviceTokensView from '../AdminDeviceTokensView.vue'
import { api, request } from '@/services/api'
import QRCode from 'qrcode'

vi.mock('@/services/api', () => ({ request: vi.fn(), api: { getAppGroups: vi.fn() } }))
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn() } }))

const SECRET = 'lellostore_dev_' + 'ab'.repeat(32)

function tokens() {
  return [
    {
      id: 'tok-1', name: 'Living room TV', subject: 'device-token:tok-1', groups: [{ id: 1, name: 'all' }],
      created_by: 'admin', created_at: '2026-10-17 10:00:00', expires_at: null, last_used_at: '2026-10-17 11:00:00', revoked_at: null,
    },
    {
      id: 'tok-2', name: 'Old box', subject: 'device-token:tok-2', groups: [],
      created_by: 'admin', created_at: '2026-10-01 10:00:00', expires_at: null, last_used_at: null, revoked_at: '2026-10-02 10:00:00',
    },
  ]
}

function groups() {
  return [
    { id: 1, name: 'all', system_kind: 'all' as const, created_at: '2026-09-09', updated_at: '2026-09-09', grants: [], user_subjects: [] },
    { id: 2, name: 'Media apps', system_kind: null, created_at: '2026-09-09', updated_at: '2026-09-09', grants: [], user_subjects: [] },
  ]
}

function mountView() {
  return shallowMount(AdminDeviceTokensView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        DefaultLayout: { template: '<main><slot /></main>' },
        VAlert: { template: '<div role="alert"><slot /></div>' },
        VChip: { template: '<span><slot /></span>' },
        VBtn: { props: ['disabled', 'type', 'to'], template: '<button :disabled="disabled" :type="type" :data-to="to ? JSON.stringify(to) : undefined"><slot /></button>' },
        VForm: { template: '<form><slot /></form>' },
        VTextField: {
          props: ['modelValue', 'label'], emits: ['update:modelValue'],
          template: '<input :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        VSelect: {
          name: 'VSelect', props: ['modelValue', 'label', 'items'], emits: ['update:modelValue'],
          template: '<select :aria-label="label" />',
        },
      },
    },
  })
}

describe('AdminDeviceTokensView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(request).mockResolvedValue(tokens())
    vi.mocked(api.getAppGroups).mockResolvedValue({ groups: groups() })
    vi.mocked(QRCode.toDataURL).mockResolvedValue('data:image/png;base64,QR' as never)
  })

  it('lists tokens with their groups and use but no secrets', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-tokens')
    const text = wrapper.text()
    expect(text).toContain('Living room TV'); expect(text).toContain('all')
    expect(text).toContain('Active'); expect(text).toContain('2026-10-17 11:00:00')
    expect(text).toContain('Revoked 2026-10-02 10:00:00'); expect(text).toContain('Never')
    expect(wrapper.find('button[aria-label="Revoke Old box"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="secret"]').exists()).toBe(false)
    const manage = wrapper.find('button[aria-label="Manage access of Living room TV"]')
    expect(JSON.parse(manage.attributes('data-to')!)).toEqual({ name: 'access-admin', query: { subject: 'device-token:tok-1' } })
    expect(wrapper.findComponent({ name: 'VSelect' }).props('items')).toEqual([
      { title: 'all', value: 1 }, { title: 'Media apps', value: 2 },
    ])
  })

  it('creates a token and shows its secret once with a copy button', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    const wrapper = mountView(); await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue(' Living room TV ')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-tokens', {
      method: 'POST', body: JSON.stringify({ name: 'Living room TV' }),
    })
    const shown = wrapper.find('[data-test="secret"]')
    expect(shown.text()).toContain(SECRET); expect(shown.text()).toContain('shown only once')
    expect(shown.text()).toContain('LELLOSTORE_DEVICE_TOKEN')
    await wrapper.findAll('button').find(b => b.text() === 'Copy token')!.trigger('click'); await flushPromises()
    expect(writeText).toHaveBeenCalledWith(SECRET)
    await wrapper.findAll('button').find(b => b.text() === 'I provisioned it')!.trigger('click')
    expect(wrapper.text()).not.toContain(SECRET)
  })

  it('renders a device-token QR for PezzotTV Companion and drops it on dismiss', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(QRCode.toDataURL).not.toHaveBeenCalled()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue('Living room TV')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    const expected = JSON.stringify({ version: 1, server_url: window.location.origin, name: 'Living room TV', token: SECRET })
    expect(QRCode.toDataURL).toHaveBeenCalledTimes(1)
    expect(vi.mocked(QRCode.toDataURL).mock.calls[0]![0]).toBe(expected)
    expect(Object.keys(JSON.parse(expected))).toEqual(['version', 'server_url', 'name', 'token'])
    expect(vi.mocked(QRCode.toDataURL).mock.calls[0]![1]).toMatchObject({ errorCorrectionLevel: 'M', width: 240 })
    const qr = wrapper.find('[data-test="secret"] [data-test="device-token-qr"] img')
    expect(qr.exists()).toBe(true); expect(qr.attributes('src')).toBe('data:image/png;base64,QR')
    expect(wrapper.find('[data-test="device-token-qr"]').text()).toContain('Scan with PezzotTV Companion')
    await wrapper.findAll('button').find(b => b.text() === 'I provisioned it')!.trigger('click')
    expect(wrapper.find('[data-test="device-token-qr"]').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(window.location.href).not.toContain(SECRET)
    expect(JSON.stringify({ ...localStorage })).not.toContain(SECRET)
  })

  it('create sends selected group ids', async () => {
    const wrapper = mountView(); await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue('TV')
    wrapper.findComponent({ name: 'VSelect' }).vm.$emit('update:modelValue', [1, 2])
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-tokens', {
      method: 'POST', body: JSON.stringify({ name: 'TV', group_ids: [1, 2] }),
    })
  })

  it('sends an expiry at the end of the chosen day', async () => {
    const wrapper = mountView(); await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue('TV')
    await wrapper.find('input[aria-label="Expires on (optional)"]').setValue('2027-01-31')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    const body = JSON.parse(vi.mocked(request).mock.calls[1]![1]!.body as string)
    expect(body.expires_at).toBe(new Date('2027-01-31T23:59:59').toISOString())
  })

  it('does not submit without a name', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('input[aria-label="Token name"]').setValue('   ')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('requires confirmation before revoking', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('button[aria-label="Revoke Living room TV"]').trigger('click')
    expect(request).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(b => b.text() === 'Confirm revocation')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/device-tokens/tok-1', { method: 'DELETE' })
    expect(wrapper.text()).toContain('Token revoked')
  })

  it('shows backend errors', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('Forbidden'))
    const wrapper = mountView(); await flushPromises(); expect(wrapper.text()).toContain('Forbidden')
  })
})
