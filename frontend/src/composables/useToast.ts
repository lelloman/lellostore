import { ref, readonly } from 'vue'

interface Toast {
  id: number
  message: string
  type: 'success' | 'error' | 'info' | 'warning'
  timeout: number
}

const toasts = ref<Toast[]>([])
let nextId = 0

function addToast(message: string, type: Toast['type'], timeout = 4000) {
  const id = nextId++
  toasts.value.push({ id, message, type, timeout })

  // Auto-remove after timeout
  setTimeout(() => {
    removeToast(id)
  }, timeout)
}

function removeToast(id: number) {
  const index = toasts.value.findIndex(t => t.id === id)
  if (index >= 0) {
    toasts.value.splice(index, 1)
  }
}

export function useToast() {
  return {
    toasts: readonly(toasts),
    success: (message: string) => addToast(message, 'success'),
    error: (message: string) => addToast(message, 'error'),
    info: (message: string) => addToast(message, 'info'),
    warning: (message: string) => addToast(message, 'warning'),
    remove: removeToast,
  }
}
