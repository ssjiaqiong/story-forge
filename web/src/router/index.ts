import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { hasToken } from '@/utils/auth-storage'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/change-password/index.vue'),
    meta: { title: '修改口令' }
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘', icon: 'Odometer', menu: true }
      },
      {
        path: 'inspiration',
        name: 'inspiration',
        component: () => import('@/views/inspiration/index.vue'),
        meta: { title: '灵感模式', icon: 'MagicStick', menu: true }
      },
      {
        path: 'bookshelf',
        name: 'bookshelf',
        component: () => import('@/views/bookshelf/index.vue'),
        meta: { title: '书架', icon: 'Collection', menu: true }
      },
      {
        // 兼容旧链接
        path: 'novels',
        redirect: '/bookshelf'
      },
      {
        path: 'novels/:id',
        name: 'novel-detail',
        component: () => import('@/views/novel/workbench.vue'),
        meta: { title: '小说编辑' }
      },
      {
        path: 'settings/ai',
        name: 'settings-ai',
        component: () => import('@/views/settings/ai/index.vue'),
        meta: { title: 'AI 接口管理', icon: 'Connection', menu: true }
      },
      {
        path: 'settings/search',
        name: 'settings-search',
        component: () => import('@/views/settings/search/index.vue'),
        meta: { title: '联网搜索', icon: 'Search', menu: true }
      },
      {
        path: 'settings/prompts',
        name: 'settings-prompts',
        component: () => import('@/views/settings/prompts/index.vue'),
        meta: { title: '提示词模板', icon: 'EditPen', menu: true }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/user/index.vue'),
        meta: { title: '用户管理', icon: 'User', menu: true, platformMenu: true, roles: ['SUPER_ADMIN'] }
      },
      {
        path: 'admin/novels',
        name: 'admin-novels',
        component: () => import('@/views/admin/novel/index.vue'),
        meta: { title: '小说管理', icon: 'Notebook', menu: true, platformMenu: true, roles: ['SUPER_ADMIN'] }
      },
      {
        path: 'forbidden',
        name: 'forbidden',
        component: () => import('@/views/error/403.vue'),
        meta: { title: '无权限' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/404.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  if (!hasToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const auth = useAuthStore()
  if (!auth.user) {
    try {
      await auth.loadCurrentUser()
    } catch {
      auth.reset()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  // 首登强制改密：仅放行改密页
  if (auth.mustChangePassword && to.name !== 'change-password') {
    ElMessage.warning('首次登录请先修改初始口令')
    return { name: 'change-password' }
  }

  const roles = to.meta.roles as string[] | undefined
  if (roles && roles.length > 0 && !roles.includes(auth.role ?? '')) {
    return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · StoryForge` : 'StoryForge · AI 长篇小说创作平台'
})

export default router
