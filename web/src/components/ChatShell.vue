<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Check,
  ChevronDown,
  History,
  LoaderCircle,
  MessageSquarePlus,
  MoreHorizontal,
  PanelLeftClose,
  PanelLeftOpen,
  Pencil,
  Pin,
  PinOff,
  Search,
  Trash2,
  X,
} from 'lucide-vue-next'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import AccountMenu from '@/components/AccountMenu.vue'
import BrandWordmark from '@/components/BrandWordmark.vue'
import { api } from '@/lib/api'
import type { Conversation, ConversationPage, UpdateConversationRequest } from '@/types/api'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const activeMenuId = ref<string | null>(null)
const editingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const draftTitle = ref('')
const editInput = ref<HTMLInputElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const searchText = ref('')
const searchQuery = ref('')
const sidebarCollapsed = ref(false)
const historyExpanded = ref(true)
const historyActionError = ref('')
const SIDEBAR_STORAGE_KEY = 'verityforge.chatSidebarCollapsed'
const HISTORY_SECTION_STORAGE_KEY = 'verityforge.chatHistoryExpanded'
const HISTORY_GROUPS_STORAGE_KEY = 'verityforge.chatHistoryCollapsedGroups'
let searchTimer: number | undefined

const conversationsQuery = useInfiniteQuery(
  computed(() => ({
    queryKey: ['conversations', searchQuery.value],
    initialPageParam: '',
    queryFn: ({ pageParam }: { pageParam: string }) => {
      const params = new URLSearchParams({ limit: '30' })
      if (pageParam) params.set('cursor', pageParam)
      if (searchQuery.value) params.set('query', searchQuery.value)
      return api.get<ConversationPage>(`/api/v1/conversations?${params.toString()}`)
    },
    getNextPageParam: (lastPage: ConversationPage) => lastPage.nextCursor || undefined,
  })),
)

const conversations = computed(() =>
  (conversationsQuery.data.value?.pages ?? []).flatMap((page) => page.items),
)

const groupOrder = ['已置顶', '今天', '昨天', '过去 7 天', '更早'] as const
type ConversationGroupLabel = (typeof groupOrder)[number]
const collapsedGroups = ref<Set<ConversationGroupLabel>>(new Set())

const groups = computed(() => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  const result = new Map<string, Conversation[]>()
  for (const conversation of conversations.value) {
    const updated = new Date(conversation.updatedAt).getTime()
    const key = conversation.pinned
      ? '已置顶'
      : updated >= today
        ? '今天'
        : updated >= today - day
          ? '昨天'
          : updated >= today - day * 7
            ? '过去 7 天'
            : '更早'
    const items = result.get(key) ?? []
    items.push(conversation)
    result.set(key, items)
  }
  return groupOrder.flatMap((label) => {
    const items = result.get(label)
    return items?.length ? [{ label, items }] : []
  })
})

const conversationCountLabel = computed(() => {
  if (!conversations.value.length) return ''
  return `${conversations.value.length}${conversationsQuery.hasNextPage.value ? '+' : ''}`
})

const searchActive = computed(() => Boolean(searchText.value.trim() || searchQuery.value))

const updateMutation = useMutation({
  mutationFn: ({ id, body }: { id: string; body: UpdateConversationRequest }) =>
    api.patch<Conversation>(`/api/v1/conversations/${id}`, body),
  onSuccess: async () => {
    historyActionError.value = ''
    activeMenuId.value = null
    editingId.value = null
    deletingId.value = null
    await queryClient.invalidateQueries({ queryKey: ['conversations'] })
  },
  onError: () => {
    historyActionError.value = '操作未完成，请重试'
  },
})

const deleteMutation = useMutation({
  mutationFn: (id: string) => api.delete<void>(`/api/v1/conversations/${id}`),
  onSuccess: async (_, id) => {
    historyActionError.value = ''
    if (route.query.conversation === id) await router.replace('/chat')
    activeMenuId.value = null
    deletingId.value = null
    await queryClient.invalidateQueries({ queryKey: ['conversations'] })
  },
  onError: () => {
    historyActionError.value = '删除失败，请重试'
  },
})

function startRename(conversation: Conversation) {
  activeMenuId.value = null
  deletingId.value = null
  editingId.value = conversation.id
  draftTitle.value = conversation.title
  void nextTick(() => {
    editInput.value?.focus()
    editInput.value?.select()
  })
}

function saveRename(id: string) {
  const title = draftTitle.value.trim()
  if (!title) return
  updateMutation.mutate({ id, body: { title } })
}

function togglePin(conversation: Conversation) {
  updateMutation.mutate({ id: conversation.id, body: { pinned: !conversation.pinned } })
}

function clearSearch() {
  window.clearTimeout(searchTimer)
  searchText.value = ''
  searchQuery.value = ''
}

async function focusSearch() {
  if (sidebarCollapsed.value) sidebarCollapsed.value = false
  historyExpanded.value = true
  await nextTick()
  searchInput.value?.focus()
  searchInput.value?.select()
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  activeMenuId.value = null
  editingId.value = null
  deletingId.value = null
}

function toggleHistory() {
  historyExpanded.value = !historyExpanded.value
  activeMenuId.value = null
}

function toggleGroup(label: ConversationGroupLabel) {
  const next = new Set(collapsedGroups.value)
  if (next.has(label)) next.delete(label)
  else next.add(label)
  collapsedGroups.value = next
  activeMenuId.value = null
}

function isGroupCollapsed(label: ConversationGroupLabel) {
  return !searchActive.value && collapsedGroups.value.has(label)
}

function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    void focusSearch()
    return
  }
  if (event.key === 'Escape' && activeMenuId.value) {
    activeMenuId.value = null
    return
  }
  if (event.key !== 'Escape' || document.activeElement !== searchInput.value) return
  if (searchActive.value) clearSearch()
  else searchInput.value?.blur()
}

function onGlobalPointerdown(event: PointerEvent) {
  const target = event.target
  if (target instanceof Element && target.closest('[data-conversation-menu]')) return
  activeMenuId.value = null
}

function onHistoryScroll(event: Event) {
  const target = event.currentTarget as HTMLElement
  if (
    target.scrollHeight - target.scrollTop - target.clientHeight < 120 &&
    conversationsQuery.hasNextPage.value &&
    !conversationsQuery.isFetchingNextPage.value
  ) {
    void conversationsQuery.fetchNextPage()
  }
}

function isActive(id: string) {
  return route.path === '/chat' && route.query.conversation === id
}

function formatConversationTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  const pad = (part: number) => String(part).padStart(2, '0')
  if (date.getTime() >= today - day) return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  if (date.getTime() >= today - day * 7 || date.getFullYear() === now.getFullYear()) {
    return `${pad(date.getMonth() + 1)}/${pad(date.getDate())}`
  }
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())}`
}

watch(
  () => route.fullPath,
  () => {
    activeMenuId.value = null
    deletingId.value = null
  },
)

watch(searchText, (value) => {
  window.clearTimeout(searchTimer)
  if (value.trim()) historyExpanded.value = true
  searchTimer = window.setTimeout(() => {
    searchQuery.value = value.trim()
  }, 250)
})

watch(sidebarCollapsed, (collapsed) => {
  window.localStorage.setItem(SIDEBAR_STORAGE_KEY, collapsed ? 'true' : 'false')
})

watch(historyExpanded, (expanded) => {
  window.localStorage.setItem(HISTORY_SECTION_STORAGE_KEY, expanded ? 'true' : 'false')
})

watch(collapsedGroups, (labels) => {
  window.localStorage.setItem(HISTORY_GROUPS_STORAGE_KEY, JSON.stringify([...labels]))
})

onMounted(() => {
  sidebarCollapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'true'
  historyExpanded.value = window.localStorage.getItem(HISTORY_SECTION_STORAGE_KEY) !== 'false'
  try {
    const storedGroups = JSON.parse(window.localStorage.getItem(HISTORY_GROUPS_STORAGE_KEY) ?? '[]')
    if (Array.isArray(storedGroups)) {
      collapsedGroups.value = new Set(
        storedGroups.filter((label): label is ConversationGroupLabel => groupOrder.includes(label)),
      )
    }
  } catch {
    collapsedGroups.value = new Set()
  }
  window.addEventListener('keydown', onGlobalKeydown)
  document.addEventListener('pointerdown', onGlobalPointerdown)
})

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  window.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('pointerdown', onGlobalPointerdown)
})
</script>

<template>
  <div
    class="grid h-dvh min-w-[1024px] overflow-hidden bg-paper-50 text-ink-950 transition-[grid-template-columns] duration-200"
    :class="sidebarCollapsed ? 'grid-cols-[68px_minmax(0,1fr)]' : 'grid-cols-[318px_minmax(0,1fr)]'"
    :style="{ '--chat-sidebar-width': sidebarCollapsed ? '68px' : '318px' }"
  >
    <aside class="flex h-dvh min-h-0 flex-col overflow-hidden border-r border-paper-200 bg-[#fcfdff]">
      <div class="flex h-[72px] shrink-0 items-center" :class="sidebarCollapsed ? 'justify-center' : 'justify-between px-[18px] pl-[22px]'">
        <RouterLink v-if="!sidebarCollapsed" to="/chat" aria-label="VerityForge 对话首页">
          <BrandWordmark class="text-[21px]" />
        </RouterLink>
        <button
          type="button"
          class="icon-button size-9 rounded-md text-ink-500"
          :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          @click="toggleSidebar"
        >
          <PanelLeftOpen v-if="sidebarCollapsed" :size="18" aria-hidden="true" />
          <PanelLeftClose v-else :size="18" aria-hidden="true" />
        </button>
      </div>

      <div class="shrink-0 px-3.5 pb-3.5">
        <RouterLink
          to="/chat"
          class="flex h-12 items-center rounded-[7px] bg-ink-950 text-sm font-semibold text-white shadow-[0_5px_12px_rgba(15,23,42,0.08)] transition-colors hover:bg-ink-800"
          :class="sidebarCollapsed ? 'justify-center px-0' : 'gap-2.5 px-4'"
          title="新建对话"
          @click="clearSearch"
        >
          <MessageSquarePlus :size="18" aria-hidden="true" />
          <span v-if="!sidebarCollapsed">新建对话</span>
        </RouterLink>
      </div>

      <div v-if="!sidebarCollapsed" class="shrink-0 border-b border-paper-200 px-3.5 pb-3.5">
        <div class="relative flex h-[41px] items-center">
          <Search :size="17" class="pointer-events-none absolute left-3 text-ink-500" aria-hidden="true" />
          <input
            ref="searchInput"
            v-model="searchText"
            type="search"
            class="h-full w-full rounded-[7px] border border-paper-300 bg-white pl-10 pr-[74px] text-[13px] text-ink-900 outline-none transition-[border-color,box-shadow] placeholder:text-ink-500 focus:border-brand-200 focus:shadow-[0_0_0_3px_rgba(37,99,235,0.07)]"
            placeholder="搜索标题或提问内容"
            aria-label="搜索对话"
          />
          <button
            v-if="searchActive"
            type="button"
            class="icon-button absolute right-1.5 size-7 rounded-md"
            title="清除搜索"
            aria-label="清除搜索"
            @click="clearSearch"
          >
            <X :size="14" aria-hidden="true" />
          </button>
          <span
            v-else
            class="pointer-events-none absolute right-2.5 inline-flex h-[23px] items-center rounded-[5px] border border-paper-200 bg-paper-100 px-1.5 text-[10px] font-medium text-ink-400"
          >
            Ctrl K
          </span>
        </div>
      </div>
      <div v-else class="shrink-0 border-b border-paper-200 px-3 pb-3">
        <button
          type="button"
          class="icon-button size-11 w-full rounded-[7px] border border-paper-200 bg-white text-ink-600"
          title="搜索对话"
          aria-label="搜索对话"
          @click="focusSearch"
        >
          <Search :size="18" aria-hidden="true" />
        </button>
      </div>

      <div v-if="!sidebarCollapsed" class="flex min-h-0 flex-1 flex-col">
        <button
          type="button"
          class="flex h-12 shrink-0 items-center justify-between px-[22px] text-left transition-colors hover:bg-paper-100/70"
          :aria-expanded="historyExpanded"
          aria-controls="conversation-history"
          @click="toggleHistory"
        >
          <div class="flex items-baseline gap-2">
            <p class="text-[13px] font-semibold text-ink-700">最近对话</p>
            <span v-if="conversationCountLabel" class="text-[10px] font-medium tabular-nums text-ink-400">
              {{ conversationCountLabel }}
            </span>
          </div>
          <span class="flex items-center gap-2">
            <LoaderCircle
              v-if="conversationsQuery.isFetching.value && !conversationsQuery.isFetchingNextPage.value"
              :size="13"
              class="animate-spin text-ink-400"
              aria-label="正在刷新历史记录"
            />
            <ChevronDown
              :size="15"
              class="text-ink-400 transition-transform duration-200"
              :class="historyExpanded ? '' : '-rotate-90'"
              aria-hidden="true"
            />
          </span>
        </button>

        <div
          v-show="historyExpanded"
          id="conversation-history"
          class="chat-history-scrollbar min-h-0 flex-1 overflow-y-auto px-3 pb-4"
          data-testid="conversation-history"
          @scroll.passive="onHistoryScroll"
        >
          <div
            v-if="historyActionError"
            class="mb-2 flex min-h-9 items-center gap-2 rounded-md bg-coral-50 px-3 text-xs font-medium text-coral-700"
            role="alert"
          >
            <span class="min-w-0 flex-1">{{ historyActionError }}</span>
            <button
              type="button"
              class="icon-button size-6 shrink-0 text-coral-700"
              title="关闭提示"
              aria-label="关闭提示"
              @click="historyActionError = ''"
            >
              <X :size="13" aria-hidden="true" />
            </button>
          </div>
          <div v-if="conversationsQuery.isPending.value" class="space-y-1.5 py-1">
            <div v-for="item in 5" :key="item" class="h-[45px] animate-pulse rounded-md bg-paper-100" />
          </div>
          <div v-else-if="conversationsQuery.isError.value" class="rounded-md bg-coral-50 px-3 py-3">
            <p class="text-xs leading-5 text-coral-700">历史记录暂时无法加载</p>
            <button
              type="button"
              class="mt-1 text-xs font-semibold text-coral-700 underline underline-offset-2"
              @click="conversationsQuery.refetch()"
            >
              重新加载
            </button>
          </div>
          <div v-else-if="!conversations.length" class="px-3 py-8 text-center">
            <Search v-if="searchActive" :size="19" class="mx-auto mb-2 text-ink-300" aria-hidden="true" />
            <History v-else :size="19" class="mx-auto mb-2 text-ink-300" aria-hidden="true" />
            <p class="text-xs font-medium text-ink-500">
              {{ searchActive ? '没有找到相关对话' : '你的对话会保存在这里' }}
            </p>
            <button
              v-if="searchActive"
              type="button"
              class="mt-2 text-xs font-semibold text-brand-700 hover:text-brand-800"
              @click="clearSearch"
            >
              清除搜索
            </button>
          </div>

          <section v-for="(group, groupIndex) in groups" v-else :key="group.label" class="pb-3 last:pb-0">
            <h2 class="sticky top-0 z-20 bg-[#fcfdff]/95 backdrop-blur-sm">
              <button
                type="button"
                class="flex h-[33px] w-full items-center gap-2 px-2 text-left transition-colors hover:text-ink-950"
                :aria-expanded="!isGroupCollapsed(group.label)"
                :aria-controls="`conversation-group-${groupIndex}`"
                @click="toggleGroup(group.label)"
              >
                <span class="text-[12px] font-semibold text-ink-600">{{ group.label }}</span>
                <span class="text-[10px] font-medium tabular-nums text-ink-400">{{ group.items.length }}</span>
                <span class="h-px min-w-0 flex-1 bg-paper-200" aria-hidden="true" />
                <ChevronDown
                  :size="14"
                  class="shrink-0 text-ink-400 transition-transform duration-200"
                  :class="isGroupCollapsed(group.label) ? '-rotate-90' : ''"
                  aria-hidden="true"
                />
              </button>
            </h2>
            <div
              v-show="!isGroupCollapsed(group.label)"
              :id="`conversation-group-${groupIndex}`"
              class="space-y-1"
            >
              <div
                v-for="conversation in group.items"
                :key="conversation.id"
                class="group relative"
              >
                <div
                  v-if="editingId === conversation.id"
                  class="flex h-[45px] items-center gap-1 rounded-md border border-brand-200 bg-white px-2"
                >
                  <input
                    ref="editInput"
                    v-model="draftTitle"
                    class="min-w-0 flex-1 bg-transparent px-1 text-xs text-ink-900 outline-none"
                    maxlength="200"
                    aria-label="会话标题"
                    @keydown.enter.prevent="saveRename(conversation.id)"
                    @keydown.esc.prevent="editingId = null"
                  />
                  <button class="icon-button size-7" type="button" title="保存标题" @click="saveRename(conversation.id)">
                    <Check :size="14" aria-hidden="true" />
                  </button>
                  <button class="icon-button size-7" type="button" title="取消" @click="editingId = null">
                    <X :size="14" aria-hidden="true" />
                  </button>
                </div>

                <div
                  v-else-if="deletingId === conversation.id"
                  class="flex h-[45px] items-center gap-1 rounded-md bg-coral-50 px-3"
                >
                  <span class="min-w-0 flex-1 text-xs font-medium text-coral-700">确认删除？</span>
                  <button
                    class="h-7 rounded-md px-2 text-xs font-semibold text-coral-700 hover:bg-white"
                    type="button"
                    @click="deleteMutation.mutate(conversation.id)"
                  >
                    删除
                  </button>
                  <button class="icon-button size-7 text-ink-500" type="button" title="取消" @click="deletingId = null">
                    <X :size="14" aria-hidden="true" />
                  </button>
                </div>

                <template v-else>
                  <RouterLink
                    :to="{ path: '/chat', query: { conversation: conversation.id } }"
                    class="flex h-[45px] items-center rounded-md border-l-[3px] px-3 pr-11 text-[13px] font-medium transition-colors"
                    :class="isActive(conversation.id) ? 'border-brand-600 bg-brand-50 text-ink-950' : 'border-transparent text-ink-700 hover:bg-paper-100 hover:text-ink-950'"
                    :title="conversation.title"
                    :aria-current="isActive(conversation.id) ? 'page' : undefined"
                  >
                    <Pin v-if="conversation.pinned" :size="12" class="mr-1.5 shrink-0 text-brand-600" aria-hidden="true" />
                    <span class="min-w-0 flex-1 truncate">{{ conversation.title }}</span>
                    <span
                      class="ml-2 shrink-0 text-[10px] font-normal tabular-nums"
                      :class="isActive(conversation.id) ? 'text-brand-700' : 'text-ink-400'"
                    >
                      {{ formatConversationTime(conversation.updatedAt) }}
                    </span>
                  </RouterLink>
                  <button
                    type="button"
                    data-conversation-menu
                    class="icon-button absolute right-1.5 top-[6px] size-8"
                    :class="isActive(conversation.id) || activeMenuId === conversation.id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 focus:opacity-100'"
                    title="会话操作"
                    :aria-expanded="activeMenuId === conversation.id"
                    @click.stop="activeMenuId = activeMenuId === conversation.id ? null : conversation.id"
                  >
                    <MoreHorizontal :size="15" aria-hidden="true" />
                  </button>
                  <div
                    v-if="activeMenuId === conversation.id"
                    data-conversation-menu
                    class="absolute right-1.5 top-[43px] z-40 w-36 rounded-lg border border-paper-200 bg-white p-1 shadow-panel"
                  >
                    <button class="history-menu-item" type="button" @click="togglePin(conversation)">
                      <PinOff v-if="conversation.pinned" :size="14" aria-hidden="true" />
                      <Pin v-else :size="14" aria-hidden="true" />
                      {{ conversation.pinned ? '取消置顶' : '置顶' }}
                    </button>
                    <button class="history-menu-item" type="button" @click="startRename(conversation)">
                      <Pencil :size="14" aria-hidden="true" />
                      重命名
                    </button>
                    <button
                      class="history-menu-item text-coral-700 hover:bg-coral-50"
                      type="button"
                      @click="activeMenuId = null; deletingId = conversation.id"
                    >
                      <Trash2 :size="14" aria-hidden="true" />
                      删除
                    </button>
                  </div>
                </template>
              </div>
            </div>
          </section>

          <div v-if="conversationsQuery.isFetchingNextPage.value" class="flex justify-center py-3">
            <LoaderCircle :size="16" class="animate-spin text-ink-400" aria-label="正在加载更多" />
          </div>
          <button
            v-else-if="conversationsQuery.hasNextPage.value"
            type="button"
            class="mx-auto mt-2 block border-t border-paper-200 px-6 py-3 text-xs font-semibold text-ink-500 hover:text-ink-900"
            @click="conversationsQuery.fetchNextPage()"
          >
            {{ searchActive ? '加载更多结果' : '查看更多历史' }}
          </button>
        </div>
      </div>

      <div v-else class="flex min-h-0 flex-1 flex-col items-center pt-3">
        <button
          type="button"
          class="icon-button size-11 rounded-[7px] text-ink-500 hover:bg-paper-100 hover:text-ink-900"
          title="查看最近对话"
          aria-label="查看最近对话"
          @click="toggleSidebar"
        >
          <History :size="19" aria-hidden="true" />
        </button>
      </div>

      <div class="shrink-0 border-t border-paper-200 p-2.5">
        <AccountMenu workspace="chat" :compact="sidebarCollapsed" />
      </div>
    </aside>

    <main class="h-dvh min-h-0 min-w-0 overflow-hidden bg-white">
      <RouterView />
    </main>
  </div>
</template>
