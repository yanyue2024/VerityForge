<script setup lang="ts">
import { computed } from 'vue'
import {
  BrainCircuit,
  Database,
  FlaskConical,
  MessageSquareText,
  Settings2,
  UsersRound,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import AccountMenu from '@/components/AccountMenu.vue'
import BrandWordmark from '@/components/BrandWordmark.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()

const workspaceNavigation = [
  { label: '知识库', to: '/knowledge', icon: Database, match: ['/knowledge'] },
  { label: '评测', to: '/evaluation', icon: FlaskConical, match: ['/evaluation'] },
  { label: '长期记忆', to: '/memory', icon: BrainCircuit, match: ['/memory'] },
]

const governanceNavigation = computed(() =>
  auth.isAdmin
    ? [
        { label: 'AI 配置', to: '/pipeline', icon: Settings2, match: ['/pipeline'] },
        { label: '成员', to: '/team', icon: UsersRound, match: ['/team'] },
      ]
    : [],
)

function isActive(match: string[]) {
  return match.some((path) => route.path.startsWith(path))
}
</script>

<template>
  <div class="grid h-dvh min-w-[1024px] grid-cols-[224px_minmax(0,1fr)] overflow-hidden bg-paper-50 text-ink-950">
    <aside class="flex h-dvh min-h-0 flex-col border-r border-paper-200 bg-white px-3 py-3">
      <RouterLink to="/knowledge" class="flex h-12 items-center px-2" aria-label="VerityForge 管理工作台">
        <BrandWordmark />
      </RouterLink>

      <RouterLink
        to="/chat"
        class="mt-2 flex h-10 items-center gap-2.5 rounded-lg border border-paper-200 px-3 text-sm font-semibold text-ink-800 transition-colors hover:border-paper-300 hover:bg-paper-100"
      >
        <MessageSquareText :size="16" aria-hidden="true" />
        返回对话
      </RouterLink>

      <nav class="mt-7" aria-label="管理工作台导航">
        <p class="px-2 text-[11px] font-semibold text-ink-400">工作台</p>
        <div class="mt-2 space-y-1">
          <RouterLink
            v-for="item in workspaceNavigation"
            :key="item.to"
            :to="item.to"
            class="management-nav-item"
            :class="isActive(item.match) ? 'management-nav-item-active' : ''"
            :aria-current="isActive(item.match) ? 'page' : undefined"
          >
            <component :is="item.icon" :size="17" aria-hidden="true" />
            {{ item.label }}
          </RouterLink>
        </div>

        <template v-if="governanceNavigation.length">
          <div class="mx-2 my-6 border-t border-paper-200" />
          <p class="px-2 text-[11px] font-semibold text-ink-400">系统管理</p>
          <div class="mt-2 space-y-1">
            <RouterLink
              v-for="item in governanceNavigation"
              :key="item.to"
              :to="item.to"
              class="management-nav-item"
              :class="isActive(item.match) ? 'management-nav-item-active' : ''"
              :aria-current="isActive(item.match) ? 'page' : undefined"
            >
              <component :is="item.icon" :size="17" aria-hidden="true" />
              {{ item.label }}
            </RouterLink>
          </div>
        </template>
      </nav>

      <div class="mt-auto border-t border-paper-200 pt-2">
        <AccountMenu workspace="management" />
      </div>
    </aside>

    <main class="scrollbar-subtle h-dvh min-h-0 min-w-0 overflow-y-auto overflow-x-hidden overscroll-contain">
      <RouterView />
    </main>
  </div>
</template>
