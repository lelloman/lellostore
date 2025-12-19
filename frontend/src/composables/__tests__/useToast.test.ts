import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useToast } from '../useToast'

describe('useToast', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('adds success toast', () => {
    const toast = useToast()

    toast.success('Operation successful')

    expect(toast.toasts.value).toHaveLength(1)
    expect(toast.toasts.value[0].message).toBe('Operation successful')
    expect(toast.toasts.value[0].type).toBe('success')
  })

  it('adds error toast', () => {
    const toast = useToast()

    toast.error('Something went wrong')

    const lastToast = toast.toasts.value[toast.toasts.value.length - 1]
    expect(lastToast.message).toBe('Something went wrong')
    expect(lastToast.type).toBe('error')
  })

  it('adds info toast', () => {
    const toast = useToast()

    toast.info('Information message')

    const lastToast = toast.toasts.value[toast.toasts.value.length - 1]
    expect(lastToast.message).toBe('Information message')
    expect(lastToast.type).toBe('info')
  })

  it('adds warning toast', () => {
    const toast = useToast()

    toast.warning('Warning message')

    const lastToast = toast.toasts.value[toast.toasts.value.length - 1]
    expect(lastToast.message).toBe('Warning message')
    expect(lastToast.type).toBe('warning')
  })

  it('removes toast after timeout', () => {
    const toast = useToast()
    const initialLength = toast.toasts.value.length

    toast.success('Temporary message')

    expect(toast.toasts.value.length).toBe(initialLength + 1)

    // Fast forward past timeout
    vi.advanceTimersByTime(5000)

    expect(toast.toasts.value.length).toBe(initialLength)
  })

  it('can manually remove toast', () => {
    const toast = useToast()

    toast.success('To be removed')
    const addedToast = toast.toasts.value[toast.toasts.value.length - 1]

    toast.remove(addedToast.id)

    expect(toast.toasts.value.find(t => t.id === addedToast.id)).toBeUndefined()
  })

  it('assigns unique ids to toasts', () => {
    const toast = useToast()

    toast.success('First')
    toast.error('Second')
    toast.info('Third')

    const ids = toast.toasts.value.slice(-3).map(t => t.id)
    const uniqueIds = new Set(ids)

    expect(uniqueIds.size).toBe(3)
  })

  it('can have multiple toasts at once', () => {
    const toast = useToast()
    const initialLength = toast.toasts.value.length

    toast.success('First')
    toast.error('Second')
    toast.warning('Third')

    expect(toast.toasts.value.length).toBe(initialLength + 3)
  })
})
