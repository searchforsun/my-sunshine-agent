import { createRouter, createWebHistory } from 'vue-router'
export default createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: 'AI 对话' } },
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { title: '知识库' } },
  { path: '/status', name: 'status', component: () => import('../views/StatusView.vue'), meta: { title: '系统状态' } },
]})
