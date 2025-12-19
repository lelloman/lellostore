import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: { name: 'apps' },
  },
  {
    path: '/apps',
    name: 'apps',
    component: () => import('@/views/AppsListView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/apps/:packageName',
    name: 'app-detail',
    component: () => import('@/views/AppDetailView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { guest: true },
  },
  {
    path: '/callback',
    name: 'callback',
    component: () => import('@/views/CallbackView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// Track if we've initialized auth
let authInitialized = false

router.beforeEach(async (to, _from, next) => {
  const authStore = useAuthStore()

  // Initialize auth once on first navigation
  if (!authInitialized) {
    authInitialized = true
    await authStore.initialize()
  }

  // Wait for auth loading to complete
  if (authStore.isLoading) {
    // Wait a bit for loading to complete
    await new Promise<void>((resolve) => {
      const checkLoading = () => {
        if (!authStore.isLoading) {
          resolve()
        } else {
          setTimeout(checkLoading, 50)
        }
      }
      checkLoading()
    })
  }

  // Check if route requires authentication
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    // Save intended destination for after login
    sessionStorage.setItem('redirectPath', to.fullPath)
    next({ name: 'login' })
    return
  }

  // Redirect authenticated users away from guest-only pages
  if (to.meta.guest && authStore.isAuthenticated) {
    next({ name: 'apps' })
    return
  }

  next()
})

export default router
