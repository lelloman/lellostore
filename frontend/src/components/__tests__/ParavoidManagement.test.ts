import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ParavoidManagement from '../ParavoidManagement.vue'
import { api, type AppDistribution } from '@/services/api'
vi.mock('@/services/api', () => ({ api: { setArtifactArchived: vi.fn(), getAppDistribution: vi.fn(), publishVpk: vi.fn() } }))
const snapshot = { distribution_mode: 'paravoid', publication_revision: 7, contracts: [], streams: [], grants: [], events: [], releases: [{ id: 'draft', payload_version: 2, release_id: 'release', publication_state: 'draft', validation_state: 'verified', release_notes: '', archive_size: 3 }] } as unknown as AppDistribution
function mountView() {
  const slot = { template: '<div><slot /></div>' }
  return shallowMount(ParavoidManagement, { props: { packageName: 'example.app' }, global: { stubs: {
    VCard: slot, VCardText: slot, VCardActions: slot, VAlert: slot,
    VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    VBtn: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  } } })
}
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.getAppDistribution).mockResolvedValue(structuredClone(snapshot)) })
it('uses the reviewed revision even after background data refresh', async () => {
  const wrapper = mountView(); await flushPromises()
  const click = async (text: string) => { await wrapper.findAll('button').find(b => b.text() === text)!.trigger('click'); await flushPromises() }
  await click('Review')
  vi.mocked(api.getAppDistribution).mockResolvedValue({ ...snapshot, publication_revision: 8 })
  await click('Refresh')
  await click('Publish payload')
  expect(api.publishVpk).toHaveBeenCalledWith('example.app', 'draft', 7, false)
})
it('does not open stale review data when refresh fails', async () => {
  const wrapper = mountView(); await flushPromises()
  vi.mocked(api.getAppDistribution).mockRejectedValue(new Error('Connection unavailable'))
  await wrapper.findAll('button').find(b => b.text() === 'Review')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('Connection unavailable')
  expect(wrapper.findAll('button').some(b => b.text() === 'Publish payload')).toBe(false)
})
it('blocks publication of merely inspected payloads', async () => {
  const incomplete = structuredClone(snapshot); incomplete.releases[0]!.validation_state = 'inspected'
  vi.mocked(api.getAppDistribution).mockResolvedValue(incomplete)
  const wrapper = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === 'Review')!.trigger('click'); await flushPromises()
  expect(wrapper.findAll('button').find(b => b.text() === 'Publish payload')!.attributes('disabled')).toBeDefined()
  expect(api.publishVpk).not.toHaveBeenCalled()
})

it('archives a payload using the displayed revision', async () => {
  const wrapper = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === 'Archive')!.trigger('click'); await flushPromises()
  expect(api.setArtifactArchived).toHaveBeenCalledWith('example.app', 'vpks', 'draft', 7, true)
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('shows optional delta diagnostics without release actions', async () => {
  const withDeltas = { ...structuredClone(snapshot), dvpk: { generation: true, advertising: false }, deltas: [
    { id: 'd1', contract_id: 'c', base_vpk_id: 'base', target_vpk_id: 'draft', base_payload_version: 1, base_archive_size: 10, target_archive_size: 1048576, state: 'ready', attempts: 1, patch_size: 262144, savings: 0.75, duration_ms: 1500, encoder_version: 'reference-dvpk.py:abc', patch_sha256: 'f'.repeat(64), file_removed: false },
    { id: 'd2', contract_id: 'c', base_vpk_id: 'base', target_vpk_id: 'draft', base_payload_version: 0, base_archive_size: 10, target_archive_size: 10, state: 'skipped', attempts: 1, failure: 'Insufficient savings: 9 of 10 bytes', file_removed: false },
  ] } as unknown as AppDistribution
  vi.mocked(api.getAppDistribution).mockResolvedValue(withDeltas)
  const wrapper = mountView(); await flushPromises()
  ;(wrapper.vm as unknown as { section: string }).section = 'deltas'; await flushPromises()
  expect(wrapper.text()).toContain('Generation: on · Offers to shells: off')
  expect(wrapper.text()).toContain('75% smaller')
  expect(wrapper.text()).toContain('Insufficient savings')
  expect(wrapper.findAll('button').map(b => b.text())).toEqual(['Refresh'])
})
