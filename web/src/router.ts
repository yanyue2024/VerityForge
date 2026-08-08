import { createRouter, createWebHistory } from 'vue-router'
import ChatShell from '@/components/ChatShell.vue'
import ManagementShell from '@/components/ManagementShell.vue'
import { pinia } from '@/stores'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/api'

const devAutoLogin =
  import.meta.env.DEV &&
  import.meta.env.VITE_DEV_AUTO_LOGIN === 'true' &&
  Boolean(import.meta.env.VITE_DEV_AUTO_LOGIN_USERNAME) &&
  Boolean(import.meta.env.VITE_DEV_AUTO_LOGIN_PASSWORD)
let autoLoginRequest: Promise<unknown> | null = null

async function ensureDevLogin() {
  const auth = useAuthStore(pinia)
  if (!devAutoLogin) return
  const expiresAt = auth.session?.expiresAt
    ? new Date(auth.session.expiresAt).getTime()
    : 0
  const longLivedSession = expiresAt > Date.now() + 365 * 24 * 60 * 60 * 1000
  if (auth.isAuthenticated && longLivedSession) return

  autoLoginRequest ??= auth
    .login(
      {
        username: import.meta.env.VITE_DEV_AUTO_LOGIN_USERNAME!,
        password: import.meta.env.VITE_DEV_AUTO_LOGIN_PASSWORD!,
      },
      true,
    )
    .finally(() => {
      autoLoginRequest = null
    })
  await autoLoginRequest
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: ChatShell,
      children: [
        {
          path: '',
          redirect: '/chat',
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/pages/ChatPage.vue'),
          meta: { title: '对话' },
        },
        {
          path: 'research/:runId',
          name: 'research',
          component: () => import('@/pages/ResearchPage.vue'),
          meta: { title: '研究运行' },
        },
      ],
    },
    {
      path: '/',
      component: ManagementShell,
      children: [
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/pages/KnowledgePage.vue'),
          meta: { title: '知识库' },
        },
        {
          path: 'knowledge/:id',
          name: 'knowledge-detail',
          component: () => import('@/pages/KnowledgeDetailPage.vue'),
          meta: { title: '知识库详情' },
        },
        {
          path: 'knowledge/:id/documents/:documentId',
          name: 'document-detail',
          redirect: (to) => {
            const legacyTab = typeof to.query.tab === 'string' ? to.query.tab : 'original'
            const documentView = ['original', 'content', 'chunks', 'metadata', 'processing'].includes(legacyTab)
              ? legacyTab
              : 'original'
            return {
              name: 'knowledge-detail',
              params: { id: to.params.id },
              query: {
                document: String(to.params.documentId),
                ...(documentView === 'original' ? {} : { documentView }),
                ...(typeof to.query.chunk === 'string' ? { chunk: to.query.chunk } : {}),
                ...(typeof to.query.page === 'string' ? { page: to.query.page } : {}),
                ...(typeof to.query.sourceStart === 'string' ? { sourceStart: to.query.sourceStart } : {}),
                ...(typeof to.query.sourceEnd === 'string' ? { sourceEnd: to.query.sourceEnd } : {}),
              },
            }
          },
        },
        {
          path: 'evaluation',
          name: 'evaluation',
          component: () => import('@/pages/EvaluationPage.vue'),
          meta: { title: '评测' },
        },
        {
          path: 'evaluation/new',
          name: 'evaluation-new',
          component: () => import('@/pages/EvaluationCreatePage.vue'),
          meta: { title: '新建评测' },
        },
        {
          path: 'evaluation/datasets/:datasetId',
          name: 'evaluation-dataset',
          component: () => import('@/pages/EvaluationDatasetPage.vue'),
          meta: { title: '评测数据集' },
        },
        {
          path: 'evaluation/runs/:runId',
          name: 'evaluation-run',
          component: () => import('@/pages/EvaluationRunPage.vue'),
          meta: { title: '评测详情' },
        },
        {
          path: 'evaluation/runs/:runId/cases/:caseId',
          name: 'evaluation-case',
          component: () => import('@/pages/EvaluationSamplePage.vue'),
          meta: { title: '样例详情' },
        },
        {
          path: 'memory',
          name: 'memory',
          component: () => import('@/pages/MemoryPage.vue'),
          meta: { title: '长期记忆' },
        },
        {
          path: 'team',
          name: 'team',
          component: () => import('@/pages/TeamPage.vue'),
          meta: { title: '团队成员', roles: ['ADMIN'] },
        },
        {
          path: 'security',
          name: 'security',
          component: () => import('@/pages/SecurityPage.vue'),
          meta: { title: '凭据安全', roles: ['ADMIN'] },
        },
        {
          path: 'pipeline',
          name: 'pipeline-config',
          component: () => import('@/pages/PipelineConfigPage.vue'),
          meta: { title: 'AI 配置', roles: ['ADMIN'] },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/chat',
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  if (devAutoLogin) {
    try {
      await ensureDevLogin()
    } catch {
      // Fall through to the regular login page if local auto-login is misconfigured.
    }
  }
  if (!to.meta.public && !auth.isAuthenticated) {
    return {
      name: 'login',
      query: to.fullPath === '/chat' ? undefined : { redirect: to.fullPath },
    }
  }
  const roles = to.meta.roles as UserRole[] | undefined
  if (roles?.length && (!auth.session || !roles.includes(auth.session.role))) {
    return { name: 'chat' }
  }
  if (to.name === 'login' && auth.isAuthenticated) return { name: 'chat' }
  return true
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '工作台')} · VerityForge`
})

export default router
