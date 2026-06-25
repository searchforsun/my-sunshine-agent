import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/authStore'
import MainLayout from '../layouts/MainLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
      meta: { public: true, title: '注册' },
    },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/chat' },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('../views/ChatView.vue'),
          meta: { title: 'AI 对话' },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('../views/KnowledgeView.vue'),
          meta: { title: '知识库' },
        },
        {
          path: 'skills',
          name: 'skills',
          component: () => import('../views/SkillsView.vue'),
          meta: { title: 'Skills' },
        },
        {
          path: 'skills/:skillId/diff',
          name: 'skill-diff',
          component: () => import('../views/SkillVersionDiffView.vue'),
          meta: { title: '版本对比' },
        },
        {
          path: 'plans/:planId',
          name: 'plan-detail',
          component: () => import('../views/PlanDetailView.vue'),
          meta: { title: '执行计划' },
        },
        {
          path: 'status',
          name: 'status',
          component: () => import('../views/StatusView.vue'),
          meta: { title: '系统状态' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    await auth.fetchMe()
  }
  const isPublic = to.meta.public === true
  if (!isPublic && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (isPublic && auth.isLoggedIn && (to.name === 'login' || to.name === 'register')) {
    return { path: '/chat' }
  }
  return true
})

export default router
