import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ReleaseManagement from '../ReleaseManagement.vue'
import { api, type App } from '@/services/api'

vi.mock('@/services/api', () => ({ api: {
  getAppDistribution: vi.fn(), getAdminApp: vi.fn(), publishRelease: vi.fn(), withdrawRelease: vi.fn(),
  saveDraft: vi.fn(), getPublicationHistory: vi.fn(), getDistributionReviews: vi.fn(), reviewDistributionTransition: vi.fn(),
} }))

const draft: App = {
  package_name: 'example.app', name: 'Example', icon_url: '/icon', publication_revision: 7,
  distribution_mode: 'normal', versions: [{ version_code: 2, version_name: '2.0', size: 100,
    sha256: 'a'.repeat(64), min_sdk: 24, uploaded_at: '2026-09-22', apk_url: '/apk',
    publication_state: 'draft', release_notes: '', is_beta: false }],
}

function mountView() {
  return shallowMount(ReleaseManagement, { props: { app: structuredClone(draft) }, global: { stubs: {
    VCard: { template: '<section><slot /></section>' },
    VWindow: { template: '<div><slot /></div>' }, VWindowItem: { template: '<div><slot /></div>' },
    VSelect: { props: ['modelValue', 'label', 'items', 'disabled'], emits: ['update:modelValue'], template: '<select :aria-label="label" :disabled="disabled" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value=""></option><option v-for="item in items" :key="item.value" :value="item.value">{{ item.title }}</option></select>' },
    VBtn: { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
    VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    VCardText: { template: '<div><slot /></div>' }, VCardActions: { template: '<footer><slot /></footer>' },
    VCardTitle: { template: '<h2><slot /></h2>' }, VAlert: { template: '<aside><slot /></aside>' },
    VDataTable: { props: ['items'], template: '<div><div v-for="item in items" :key="item.version_code"><slot name="item.actions" :item="item" /></div></div>' },
  } } })
}

describe('release review', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getAdminApp).mockResolvedValue(structuredClone(draft))
    vi.mocked(api.getPublicationHistory).mockResolvedValue([])
    vi.mocked(api.getDistributionReviews).mockResolvedValue([])
  })

  it('requires migration review before switching an installed distribution', async () => {
    const changed = structuredClone(draft)
    changed.distribution_mode = 'paravoid'
    vi.mocked(api.getAdminApp).mockResolvedValue(changed)
    const wrapper = mountView()
    await wrapper.findAll('button').find(b => b.text() === 'Review draft')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('changes distribution from paravoid to normal')
    const publish = wrapper.findAll('button').find(b => b.text() === 'Publish release')!
    expect(publish.attributes('disabled')).toBeDefined()
    expect(api.publishRelease).not.toHaveBeenCalled()
  })

  it('does not publish until the administrator reviews and explicitly publishes', async () => {
    const wrapper = mountView()
    expect(api.publishRelease).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === 'Review draft')!.trigger('click')
    await flushPromises()
    expect(api.getAdminApp).toHaveBeenCalledWith('example.app')
    expect(wrapper.text()).toContain('Review release')
    expect(api.publishRelease).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === 'Publish release')!.trigger('click')
    await flushPromises()
    expect(api.publishRelease).toHaveBeenCalledWith('example.app', 2, 7, false, undefined, undefined)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it.each(['empty', 'embedded'])('publishes the reviewed %s bootstrap with its installer', async (bootstrap) => {
    const shell = structuredClone(draft)
    shell.versions[0]!.distribution_mode = 'paravoid'
    vi.mocked(api.getAdminApp).mockResolvedValue(shell)
    vi.mocked(api.getAppDistribution).mockResolvedValue({
      distribution_mode: 'normal', publication_revision: 7,
      installers: [{ installer_version: 2, contract_id: 'contract', embedded_vpk_id: bootstrap === 'embedded' ? 'bootstrap' : null }],
      contracts: [{ package_name: 'example.app', contract_id: 'contract', installer_version: 2,
        channel: 'stable', authentication: 'public', bootstrap, base_url: 'https://example.test/', verification_state: 'verified', validation_report: '{}' }],
      releases: [{ id: 'bootstrap', package_name: 'example.app', contract_id: 'contract', release_id: 'r1', payload_version: 1,
        archive_size: 1, archive_sha256: 'hash', manifest_sha256: 'manifest', manifest_json: '{}', min_sdk: 30, max_sdk: 0,
        abis_json: '[]', signing_key_id: 'release', validation_state: 'verified', validation_report: '{}', publication_state: 'draft', release_notes: '' }],
      streams: [], grants: [], events: [],
    })
    const wrapper = mountView()
    await wrapper.findAll('button').find(b => b.text() === 'Review draft')!.trigger('click')
    await flushPromises()
    const publish = wrapper.findAll('button').find(b => b.text() === 'Publish release')!
    if (bootstrap === 'empty') {
      expect(publish.attributes('disabled')).toBeDefined()
      await wrapper.find('select[aria-label="Bootstrap payload"]').setValue('bootstrap')
    } else {
      expect(wrapper.text()).toContain('includes its verified bootstrap payload')
    }
    expect(publish.attributes('disabled')).toBeUndefined()
    await publish.trigger('click')
    await flushPromises()
    expect(api.publishRelease).toHaveBeenCalledWith('example.app', 2, 7, false, undefined, 'bootstrap')
  })

  it('keeps the review and reports a stale publication failure', async () => {
    vi.mocked(api.publishRelease).mockRejectedValue(new Error('Publication changed. Refresh the review.'))
    const wrapper = mountView()
    await wrapper.findAll('button').find(b => b.text() === 'Review draft')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === 'Publish release')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Publication changed. Refresh the review.')
    expect(wrapper.text()).toContain('Review release')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('refuses to review a draft which another admin already published', async () => {
    const changed = structuredClone(draft)
    changed.versions[0]!.publication_state = 'published'
    vi.mocked(api.getAdminApp).mockResolvedValue(changed)
    const wrapper = mountView()
    await wrapper.findAll('button').find(b => b.text() === 'Review draft')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('This release changed.')
    expect(api.publishRelease).not.toHaveBeenCalled()
  })
})
