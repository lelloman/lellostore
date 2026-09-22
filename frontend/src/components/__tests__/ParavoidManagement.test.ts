import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ParavoidManagement from '../ParavoidManagement.vue'
import { api, type AppDistribution } from '@/services/api'
vi.mock('@/services/api', () => ({ api: { getAppDistribution: vi.fn(), publishVpk: vi.fn() } }))
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
