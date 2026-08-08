<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  ChevronDown,
  FlaskConical,
  BrainCircuit,
  History,
  Library,
  LogOut,
  MessageSquarePlus,
  MessageSquareText,
  Settings2,
  UsersRound,
} from 'lucide-vue-next'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'
import { useRunStore } from '@/stores/runs'
import type { Conversation } from '@/types/api'
import BrandWordmark from '@/components/BrandWordmark.vue'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const runs = useRunStore()
const historyExpanded = ref(true)
const showAllHistory = ref(false)

const conversationNavigation = {
  label: '对话',
  to: '/chat',
  icon: MessageSquareText,
  match: ['/chat', '/research'],
}

const primaryNavigation = [
  conversationNavigation,
  { label: '知识库', to: '/knowledge', icon: Library, match: ['/knowledge'] },
  { label: '记忆', to: '/memory', icon: BrainCircuit, match: ['/memory'] },
  { label: '评测', to: '/evaluation', icon: FlaskConical, match: ['/evaluation'] },
]

const conversationsQuery = useQuery({
  queryKey: ['conversations'],
  queryFn: () => api.get<Conversation[]>('/api/v1/conversations'),
})

const historyConversations = computed(() =>
  [...(conversationsQuery.data.value ?? [])].sort(
    (left, right) =>
      new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
  ),
)

const visibleHistory = computed(() =>
  showAllHistory.value ? historyConversations.value : historyConversations.value.slice(0, 1),
)

const hasMoreHistory = computed(() => historyConversations.value.length > 1)

const governanceNavigation = computed(() =>
  auth.isAdmin
    ? [
        { label: '团队', to: '/team', icon: UsersRound, match: ['/team'] },
        { label: 'AI 配置', to: '/pipeline', icon: Settings2, match: ['/pipeline'] },
      ]
    : [],
)

const mobileNavigation = computed(() => [
  ...primaryNavigation,
  ...governanceNavigation.value.map((item) => ({
    ...item,
    label: item.label === 'AI 配置' ? '配置' : item.label,
  })),
])

function isActive(match: string[]) {
  return match.some((path) => route.path.startsWith(path))
}

function isConversationActive(id: string) {
  return route.path === '/chat' && route.query.conversation === id
}

function toggleHistory() {
  historyExpanded.value = !historyExpanded.value
  if (!historyExpanded.value) showAllHistory.value = false
}

function formatHistoryDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function logout() {
  auth.logout()
  runs.clear()
  queryClient.clear()
  void router.replace('/login')
}
</script>

<template>
  <div class="min-h-dvh bg-paper-50 text-ink-950">
    <aside
      class="scrollbar-subtle fixed inset-y-4 left-4 z-30 hidden w-60 flex-col overflow-y-auto rounded-lg bg-ink-950 px-2.5 py-3 text-white shadow-shell md:flex"
    >
      <RouterLink to="/chat" class="flex h-11 items-center px-2 text-white" title="VerityForge">
        <BrandWordmark inverted />
      </RouterLink>

      <RouterLink
        to="/chat"
        class="mt-4 flex h-10 items-center gap-2.5 rounded-lg bg-brand-600 px-3 text-sm font-semibold text-white shadow-sm transition-colors duration-200 hover:bg-brand-700"
        title="开始新对话"
      >
        <MessageSquarePlus :size="17" aria-hidden="true" />
        开始新对话
      </RouterLink>

      <p class="mt-5 px-3 text-[11px] font-medium text-white/40">工作区</p>
      <nav class="mt-1.5 space-y-0.5" aria-label="主导航">
        <RouterLink
          v-for="item in primaryNavigation"
          :key="item.to"
          :to="item.to"
          class="relative flex h-9 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors duration-200"
          :class="
            isActive(item.match)
              ? 'bg-white/10 text-white'
              : 'text-white/65 hover:bg-white/10 hover:text-white'
          "
          :aria-current="isActive(item.match) ? 'page' : undefined"
        >
          <span
            v-if="isActive(item.match)"
            class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-brand-200"
          />
          <component :is="item.icon" :size="17" aria-hidden="true" />
          {{ item.label }}
        </RouterLink>
      </nav>

      <template v-if="governanceNavigation.length">
        <div class="mx-2 mt-4 border-t border-white/10" />
        <p class="mt-4 px-3 text-[11px] font-medium text-white/40">治理与配置</p>
        <nav class="mt-1.5 space-y-0.5" aria-label="治理导航">
          <RouterLink
            v-for="item in governanceNavigation"
            :key="item.to"
            :to="item.to"
            class="relative flex h-9 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors duration-200"
            :class="
              isActive(item.match)
                ? 'bg-white/10 text-white'
                : 'text-white/65 hover:bg-white/10 hover:text-white'
            "
            :aria-current="isActive(item.match) ? 'page' : undefined"
          >
            <span
              v-if="isActive(item.match)"
              class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-brand-200"
            />
            <component :is="item.icon" :size="17" aria-hidden="true" />
            {{ item.label }}
          </RouterLink>
        </nav>
      </template>

      <div class="mx-2 mt-4 border-t border-white/10" />
      <div class="mt-3">
        <button
          type="button"
          class="flex h-9 w-full items-center gap-3 rounded-lg px-3 text-sm font-medium text-white/70 transition-colors duration-200 hover:bg-white/10 hover:text-white"
          :aria-expanded="historyExpanded"
          aria-controls="desktop-conversation-history"
          @click="toggleHistory"
        >
          <History :size="17" aria-hidden="true" />
          <span class="flex-1 text-left">历史记录</span>
          <ChevronDown
            :size="15"
            class="transition-transform duration-200"
            :class="historyExpanded ? 'rotate-180' : ''"
            aria-hidden="true"
          />
        </button>

        <div
          v-show="historyExpanded"
          id="desktop-conversation-history"
          class="mt-1 min-h-0"
        >
          <div v-if="conversationsQuery.isPending.value" class="space-y-2 px-3 py-2">
            <div v-for="index in 2" :key="index" class="h-10 animate-pulse rounded-md bg-white/5" />
          </div>
          <p
            v-else-if="conversationsQuery.isError.value"
            class="px-3 py-2 text-xs leading-5 text-coral-200"
          >
            历史记录加载失败
          </p>
          <p
            v-else-if="!historyConversations.length"
            class="px-3 py-2 text-xs text-white/40"
          >
            还没有历史会话
          </p>
          <div
            v-else
            class="space-y-0.5"
            :class="showAllHistory ? 'max-h-[min(30vh,15rem)] overflow-y-auto pr-1 scrollbar-subtle' : ''"
          >
            <RouterLink
              v-for="conversation in visibleHistory"
              :key="conversation.id"
              :to="{ path: '/chat', query: { conversation: conversation.id } }"
              class="relative block rounded-lg border-l-2 px-3 py-2 transition-colors duration-200"
              :class="
                isConversationActive(conversation.id)
                  ? 'border-brand-200 bg-white/10 text-white'
                  : 'border-transparent text-white/65 hover:bg-white/10 hover:text-white'
              "
              :title="conversation.title"
            >
              <span class="block truncate text-xs font-medium leading-5">
                {{ conversation.title }}
              </span>
              <span class="mt-0.5 block text-[11px] tabular-nums text-white/40">
                {{ formatHistoryDate(conversation.updatedAt) }}
              </span>
            </RouterLink>
          </div>
        </div>
      </div>

      <button
        v-if="hasMoreHistory && historyExpanded"
        type="button"
        class="mx-auto mt-auto flex min-h-8 items-center gap-1 rounded-lg border border-white/15 px-5 text-xs font-medium text-white/70 transition-colors duration-200 hover:border-white/30 hover:bg-white/10 hover:text-white"
        :aria-expanded="showAllHistory"
        @click="showAllHistory = !showAllHistory"
      >
        {{ showAllHistory ? '收起记录' : '查看更多' }}
        <ChevronDown
          :size="14"
          class="transition-transform duration-200"
          :class="showAllHistory ? 'rotate-180' : '-rotate-90'"
          aria-hidden="true"
        />
      </button>

      <div class="mt-3 border-t border-white/10 pt-2.5">
        <div class="flex items-center gap-3 rounded-lg px-2 py-2">
          <span
            class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white/10 text-xs font-semibold text-white"
          >
            {{ auth.session?.displayName?.slice(0, 1) || 'U' }}
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-medium">
              {{ auth.session?.displayName }}
            </span>
            <span class="block text-[11px] text-white/40">{{ auth.session?.role }}</span>
          </span>
          <button class="icon-button size-9 text-white/60 hover:bg-white/10 hover:text-white" type="button" title="退出登录" @click="logout">
            <LogOut :size="17" aria-hidden="true" />
          </button>
        </div>
      </div>
    </aside>

    <div
      class="sticky top-0 z-20 flex h-14 items-center justify-between border-b border-paper-200 bg-paper-50/95 px-4 backdrop-blur md:hidden"
    >
      <RouterLink to="/chat" class="font-semibold" title="VerityForge">
        <BrandWordmark />
      </RouterLink>
      <button class="icon-button size-9" type="button" title="退出登录" @click="logout">
        <LogOut :size="17" aria-hidden="true" />
      </button>
    </div>

    <main class="min-w-0 pb-16 md:ml-[17rem] md:min-h-dvh md:pb-0">
      <RouterView />
    </main>

    <nav
      class="fixed inset-x-0 bottom-0 z-30 grid h-16 border-t border-paper-200 bg-paper-50 md:hidden"
      :style="{ gridTemplateColumns: `repeat(${mobileNavigation.length}, minmax(0, 1fr))` }"
      aria-label="移动端主导航"
    >
      <RouterLink
        v-for="item in mobileNavigation"
        :key="item.to"
        :to="item.to"
        class="flex flex-col items-center justify-center gap-1 text-[11px] font-medium"
        :class="isActive(item.match) ? 'text-brand-700' : 'text-ink-400'"
      >
        <component :is="item.icon" :size="20" aria-hidden="true" />
        {{ item.label }}
      </RouterLink>
    </nav>
  </div>
</template>
