import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import AdminPublishTokensView from '../AdminPublishTokensView.vue'
import { request } from '@/services/api'

vi.mock('@/services/api', () => ({ request: vi.fn() }))

const SECRET = 'lellostore_pub_' + 'ab'.repeat(32)

function tokens() {
  return [
    {
      id: 'tok-1', name: 'Pezzottify CI', package_name: 'com.lelloman.pezzottify', created_by: 'admin',
      created_at: '2026-10-17 10:00:00', expires_at: null, last_used_at: '2026-10-17 11:00:00', revoked_at: null,
      recent_uploads: [{ id: 'job-1', kind: 'apk', package_name: 'com.lelloman.pezzottify', status: 'ready', error: null, created_at: '2026-10-17 11:00:00' }],
    },
    {
      id: 'tok-2', name: 'Old CI', package_name: 'com.example.old', created_by: 'admin',
      created_at: '2026-10-01 10:00:00', expires_at: null, last_used_at: null, revoked_at: '2026-10-02 10:00:00', recent_uploads: [],
    },
  ]
}

function mountView() {
  return shallowMount(AdminPublishTokensView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        DefaultLayout: { template: '<main><slot /></main>' },
        VAlert: { template: '<div role="alert"><slot /></div>' },
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

describe('AdminPublishTokensView', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(request).mockResolvedValue(tokens()) })

  it('lists tokens with their use and recent uploads but no secrets', async () => {
    const wrapper = mountView(); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/publish-tokens')
    const text = wrapper.text()
    expect(text).toContain('Pezzottify CI'); expect(text).toContain('com.lelloman.pezzottify')
    expect(text).toContain('Active'); expect(text).toContain('2026-10-17 11:00:00')
    expect(text).toContain('APK · ready'); expect(text).toContain('Revoked 2026-10-02 10:00:00')
    expect(text).toContain('Never')
    expect(wrapper.find('button[aria-label="Revoke Old CI"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="secret"]').exists()).toBe(false)
  })

  it('creates a token and shows its secret once with a copy button', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    const wrapper = mountView(); await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue(' Pezzottify CI ')
    await wrapper.find('input[aria-label="App package"]').setValue('com.lelloman.pezzottify')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/publish-tokens', {
      method: 'POST', body: JSON.stringify({ name: 'Pezzottify CI', package_name: 'com.lelloman.pezzottify' }),
    })
    const shown = wrapper.find('[data-test="secret"]')
    expect(shown.text()).toContain(SECRET); expect(shown.text()).toContain('shown only once')
    await wrapper.findAll('button').find(b => b.text() === 'Copy token')!.trigger('click'); await flushPromises()
    expect(writeText).toHaveBeenCalledWith(SECRET)
    await wrapper.findAll('button').find(b => b.text() === 'I stored it')!.trigger('click')
    expect(wrapper.text()).not.toContain(SECRET)
  })

  it('sends an expiry at the end of the chosen day', async () => {
    const wrapper = mountView(); await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ ...tokens()[0], secret: SECRET }).mockResolvedValueOnce(tokens())
    await wrapper.find('input[aria-label="Token name"]').setValue('CI')
    await wrapper.find('input[aria-label="App package"]').setValue('com.example.app')
    await wrapper.find('input[aria-label="Expires on (optional)"]').setValue('2027-01-31')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    const body = JSON.parse(vi.mocked(request).mock.calls[1]![1]!.body as string)
    expect(body.expires_at).toBe(new Date('2027-01-31T23:59:59').toISOString())
  })

  it('does not submit an invalid package', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('input[aria-label="Token name"]').setValue('CI')
    await wrapper.find('input[aria-label="App package"]').setValue('not a package')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('requires confirmation before revoking', async () => {
    const wrapper = mountView(); await flushPromises()
    await wrapper.find('button[aria-label="Revoke Pezzottify CI"]').trigger('click')
    expect(request).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(b => b.text() === 'Confirm revocation')!.trigger('click'); await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/admin/publish-tokens/tok-1', { method: 'DELETE' })
    expect(wrapper.text()).toContain('Token revoked')
  })

  it('shows backend errors', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('Forbidden'))
    const wrapper = mountView(); await flushPromises(); expect(wrapper.text()).toContain('Forbidden')
  })
})
