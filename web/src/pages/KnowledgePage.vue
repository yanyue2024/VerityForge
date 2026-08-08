<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Database,
  FileStack,
  Layers3,
  LoaderCircle,
  MoreHorizontal,
  Plus,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type { KnowledgeBase } from '@/types/api'

const queryClient = useQueryClient()
const auth = useAuthStore()
const search = ref('')
const createOpen = ref(false)
const name = ref('')
const description = ref('')
const openMenuId = ref<string | null>(null)
const deleteTarget = ref<KnowledgeBase | null>(null)
const deleteConfirmation = ref('')
const successMessage = ref('')
let successTimer: ReturnType<typeof setTimeout> | undefined

const knowledgeQuery = useQuery({
  queryKey: ['knowledge-bases'],
  queryFn: () => api.get<KnowledgeBase[]>('/api/v1/knowledge-bases'),
  refetchOnMount: 'always',
  refetchInterval: (query) => {
    const data = query.state.data as KnowledgeBase[] | undefined
    return data?.some((item) => item.processingCount > 0) ? 10_000 : false
  },
})

const filtered = computed(() => {
  const term = search.value.trim().toLowerCase()
  if (!term) return knowledgeQuery.data.value ?? []
  return (knowledgeQuery.data.value ?? []).filter(
    (item) => item.name.toLowerCase().includes(term) || item.description.toLowerCase().includes(term),
  )
})

const totals = computed(() => {
  const items = knowledgeQuery.data.value ?? []
  return items.reduce(
    (result, item) => ({
      knowledgeBases: items.length,
      documents: result.documents + item.documentCount,
      ready: result.ready + item.readyCount,
      processing: result.processing + item.processingCount,
      failed: result.failed + item.failedCount,
    }),
    { knowledgeBases: items.length, documents: 0, ready: 0, processing: 0, failed: 0 },
  )
})

const overviewStatus = computed(() => {
  if (totals.value.failed > 0) {
    return { label: `${totals.value.failed} 篇处理失败`, className: 'text-coral-700' }
  }
  if (totals.value.processing > 0) {
    return { label: `${totals.value.processing} 篇处理中`, className: 'text-amber-700' }
  }
  if (totals.value.documents === 0) {
    return { label: '等待上传文档', className: 'text-ink-500' }
  }
  return { label: '当前无异常', className: 'text-emerald-700' }
})

const deletingNonEmpty = computed(
  () => Boolean(deleteTarget.value && (deleteTarget.value.documentCount > 0 || deleteTarget.value.chunkCount > 0)),
)

const deleteAllowed = computed(() => {
  if (!deleteTarget.value) return false
  return !deletingNonEmpty.value || deleteConfirmation.value === deleteTarget.value.name
})

function cardState(item: KnowledgeBase) {
  if (item.failedCount > 0) {
    return {
      label: `${item.failedCount} 篇处理失败`,
      detail: '需要查看文档处理过程',
      railClass: 'bg-coral-700',
      dotClass: 'bg-coral-700',
      textClass: 'text-coral-700',
    }
  }
  if (item.processingCount > 0) {
    return {
      label: `${item.processingCount} 篇处理中`,
      detail: '状态将自动更新',
      railClass: 'bg-amber-500',
      dotClass: 'bg-amber-500',
      textClass: 'text-amber-700',
    }
  }
  if (item.documentCount === 0) {
    return {
      label: '尚未上传文档',
      detail: '进入知识库开始添加资料',
      railClass: 'bg-paper-300',
      dotClass: 'bg-paper-300',
      textClass: 'text-ink-600',
    }
  }
  if (item.readyCount === item.documentCount) {
    return {
      label: '全部可检索',
      detail: `${item.readyCount} / ${item.documentCount} 篇已发布`,
      railClass: 'bg-emerald-500',
      dotClass: 'bg-emerald-500',
      textClass: 'text-emerald-700',
    }
  }
  return {
    label: `${item.readyCount} / ${item.documentCount} 篇可检索`,
    detail: `${item.documentCount - item.readyCount} 篇已暂停或待发布`,
    railClass: 'bg-brand-600',
    dotClass: 'bg-brand-600',
    textClass: 'text-brand-700',
  }
}

function showSuccess(message: string) {
  successMessage.value = message
  if (successTimer) clearTimeout(successTimer)
  successTimer = setTimeout(() => {
    successMessage.value = ''
  }, 3200)
}

function openDelete(item: KnowledgeBase) {
  openMenuId.value = null
  deleteTarget.value = item
  deleteConfirmation.value = ''
  deleteMutation.reset()
}

function closeDelete() {
  if (deleteMutation.isPending.value) return
  deleteTarget.value = null
  deleteConfirmation.value = ''
}

const createMutation = useMutation({
  mutationFn: () => api.post<KnowledgeBase>('/api/v1/knowledge-bases', {
    name: name.value.trim(),
    description: description.value.trim(),
  }),
  onSuccess: async () => {
    await queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] })
    createOpen.value = false
    name.value = ''
    description.value = ''
  },
})

const deleteMutation = useMutation({
  mutationFn: (knowledgeBaseId: string) => api.delete<void>(`/api/v1/knowledge-bases/${knowledgeBaseId}`),
  onSuccess: async () => {
    const deletedName = deleteTarget.value?.name ?? '知识库'
    deleteTarget.value = null
    deleteConfirmation.value = ''
    await queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] })
    showSuccess(`“${deletedName}”已永久删除`)
  },
})

onBeforeUnmount(() => {
  if (successTimer) clearTimeout(successTimer)
})
</script>

<template>
  <div class="mx-auto w-full max-w-[1280px] px-10 py-9" @click="openMenuId = null">
    <header class="flex items-start justify-between gap-8">
      <div>
        <h1 class="text-[28px] font-semibold leading-tight text-ink-950">知识库</h1>
        <p class="mt-2 text-sm text-ink-500">组织文档、Metadata 与检索索引。</p>
      </div>
      <button v-if="auth.canEdit" type="button" class="button-primary" @click="createOpen = true">
        <Plus :size="17" aria-hidden="true" />
        新建知识库
      </button>
    </header>

    <section class="knowledge-toolbar mt-8" aria-label="知识库检索与运行摘要">
      <div class="relative w-full max-w-[430px]">
        <Search :size="17" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" />
        <input v-model="search" class="control h-11 pl-10" placeholder="搜索知识库" />
      </div>
      <div class="knowledge-summary" aria-live="polite">
        <span><strong>{{ totals.knowledgeBases }}</strong> 个知识库</span>
        <i aria-hidden="true" />
        <span><strong>{{ totals.documents }}</strong> 篇文档</span>
        <i aria-hidden="true" />
        <span><strong>{{ totals.ready }}</strong> 篇可检索</span>
        <i aria-hidden="true" />
        <span :class="overviewStatus.className">{{ overviewStatus.label }}</span>
        <button
          type="button"
          class="icon-button size-9"
          :disabled="knowledgeQuery.isFetching.value"
          title="刷新知识库状态"
          aria-label="刷新知识库状态"
          @click.stop="knowledgeQuery.refetch()"
        >
          <RefreshCw :size="16" :class="knowledgeQuery.isFetching.value ? 'animate-spin' : ''" aria-hidden="true" />
        </button>
      </div>
    </section>

    <div v-if="knowledgeQuery.isPending.value" class="knowledge-grid mt-6 grid grid-cols-2 gap-4">
      <div v-for="item in 4" :key="item" class="h-[218px] animate-pulse rounded-lg border border-paper-200 bg-white">
        <div class="h-full w-1 rounded-l-lg bg-paper-200" />
      </div>
    </div>

    <ErrorState
      v-else-if="knowledgeQuery.isError.value"
      class="mt-7"
      :message="readableError(knowledgeQuery.error.value)"
      @retry="knowledgeQuery.refetch()"
    />

    <EmptyState
      v-else-if="!filtered.length"
      class="mt-10"
      :icon="Database"
      :title="search ? '没有匹配的知识库' : '还没有知识库'"
      :description="search ? '尝试其他关键词。' : '创建知识库后即可上传文档。'"
    >
      <button v-if="auth.canEdit && !search" type="button" class="button-primary" @click="createOpen = true">
        <Plus :size="17" aria-hidden="true" />
        新建知识库
      </button>
    </EmptyState>

    <section v-else class="knowledge-grid mt-6 grid grid-cols-2 gap-4" aria-label="知识库列表">
      <article
        v-for="item in filtered"
        :key="item.id"
        class="knowledge-card group"
      >
        <span class="knowledge-card-rail" :class="cardState(item).railClass" aria-hidden="true" />
        <RouterLink :to="`/knowledge/${item.id}`" class="block h-full px-6 py-5 pr-16">
          <div class="min-w-0">
            <h2 class="truncate text-[16px] font-semibold text-ink-950 transition-colors group-hover:text-brand-700">
              {{ item.name }}
            </h2>
            <p class="mt-2 line-clamp-2 min-h-10 text-sm leading-5 text-ink-500">
              {{ item.description || '尚未添加说明。进入知识库后可以上传和管理文档。' }}
            </p>
          </div>

          <div class="mt-5 flex items-center justify-between gap-5 border-t border-paper-200 pt-4">
            <div class="min-w-0">
              <p class="inline-flex items-center gap-2 text-sm font-semibold" :class="cardState(item).textClass">
                <span class="size-2 rounded-full" :class="cardState(item).dotClass" aria-hidden="true" />
                {{ cardState(item).label }}
              </p>
              <p class="mt-1 text-xs text-ink-400">{{ cardState(item).detail }}</p>
            </div>
            <ArrowRight :size="18" class="shrink-0 text-ink-400 transition-transform group-hover:translate-x-1 group-hover:text-brand-700" aria-hidden="true" />
          </div>

          <div class="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-ink-500">
            <span class="inline-flex items-center gap-1.5">
              <FileStack :size="14" aria-hidden="true" />
              {{ item.documentCount }} 篇文档
            </span>
            <span class="inline-flex items-center gap-1.5">
              <Layers3 :size="14" aria-hidden="true" />
              {{ item.chunkCount }} 个子块
            </span>
            <span class="ml-auto inline-flex items-center gap-1.5">
              <Clock3 :size="14" aria-hidden="true" />
              {{ formatDate(item.updatedAt) }}
            </span>
          </div>
        </RouterLink>

        <div v-if="auth.isAdmin" class="absolute right-3 top-3 z-10">
          <button
            type="button"
            class="icon-button size-9"
            :aria-label="`${item.name} 的更多操作`"
            :aria-expanded="openMenuId === item.id"
            @click.stop="openMenuId = openMenuId === item.id ? null : item.id"
          >
            <MoreHorizontal :size="18" aria-hidden="true" />
          </button>
          <div
            v-if="openMenuId === item.id"
            class="knowledge-menu"
            role="menu"
            @click.stop
          >
            <button type="button" role="menuitem" @click="openDelete(item)">
              <Trash2 :size="15" aria-hidden="true" />
              删除知识库
            </button>
          </div>
        </div>
      </article>
    </section>

    <Transition name="knowledge-toast">
      <div v-if="successMessage" class="knowledge-toast" role="status">
        <CheckCircle2 :size="18" aria-hidden="true" />
        {{ successMessage }}
      </div>
    </Transition>

    <ModalDialog :open="createOpen" title="新建知识库" description="新知识库会自动继承组织统一的文档 Metadata 字段。" @close="createOpen = false">
      <form class="space-y-5" @submit.prevent="createMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">
          名称
          <input v-model="name" class="control mt-2" maxlength="120" required />
        </label>
        <label class="block text-sm font-medium text-ink-800">
          描述
          <textarea v-model="description" class="control mt-2 min-h-24 resize-y" maxlength="500" />
        </label>
        <p v-if="createMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
          {{ readableError(createMutation.error.value) }}
        </p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="createOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="createMutation.isPending.value || !name.trim()">
            <LoaderCircle v-if="createMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />
            创建
          </button>
        </div>
      </form>
    </ModalDialog>

    <ModalDialog
      :open="Boolean(deleteTarget)"
      title="删除知识库"
      :description="deletingNonEmpty ? '该操作会永久移除知识库及其全部资料。' : '确认删除这个空知识库。'"
      @close="closeDelete"
    >
      <div v-if="deleteTarget" class="space-y-5">
        <div class="flex items-start gap-3 border-l-2 border-coral-700 bg-coral-50 px-4 py-3.5">
          <AlertTriangle :size="19" class="mt-0.5 shrink-0 text-coral-700" aria-hidden="true" />
          <div>
            <p class="text-sm font-semibold text-ink-950">“{{ deleteTarget.name }}”删除后无法恢复</p>
            <p class="mt-1 text-xs leading-5 text-ink-600">
              历史对话正文会继续保留，但引用该知识库的证据将无法再次打开。
            </p>
          </div>
        </div>

        <div v-if="deletingNonEmpty" class="grid grid-cols-2 border-y border-paper-200 py-4">
          <div class="border-r border-paper-200 px-4">
            <p class="text-xs text-ink-500">文档</p>
            <p class="mt-1 text-xl font-semibold tabular-nums text-ink-950">{{ deleteTarget.documentCount }}</p>
          </div>
          <div class="px-4">
            <p class="text-xs text-ink-500">检索子块</p>
            <p class="mt-1 text-xl font-semibold tabular-nums text-ink-950">{{ deleteTarget.chunkCount }}</p>
          </div>
        </div>

        <label v-if="deletingNonEmpty" class="block text-sm font-medium text-ink-800">
          输入知识库名称以确认
          <span class="mt-1 block text-xs font-normal text-ink-500">{{ deleteTarget.name }}</span>
          <input
            v-model="deleteConfirmation"
            class="control mt-2"
            autocomplete="off"
            :placeholder="deleteTarget.name"
            @keyup.enter="deleteAllowed && deleteMutation.mutate(deleteTarget.id)"
          />
        </label>

        <p v-if="deleteMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
          {{ readableError(deleteMutation.error.value) }}
        </p>

        <div class="flex justify-end gap-3 border-t border-paper-200 pt-5">
          <button type="button" class="button-secondary" :disabled="deleteMutation.isPending.value" @click="closeDelete">取消</button>
          <button
            type="button"
            class="inline-flex min-h-10 items-center justify-center gap-2 rounded-lg bg-coral-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#9f3142] disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="deleteMutation.isPending.value || !deleteAllowed"
            @click="deleteMutation.mutate(deleteTarget.id)"
          >
            <LoaderCircle v-if="deleteMutation.isPending.value" :size="16" class="animate-spin" aria-hidden="true" />
            <Trash2 v-else :size="16" aria-hidden="true" />
            永久删除
          </button>
        </div>
      </div>
    </ModalDialog>
  </div>
</template>

<style scoped>
.knowledge-toolbar {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  border-top: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
}

.knowledge-summary {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.knowledge-summary strong {
  color: #273449;
  font-size: 14px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.knowledge-summary i {
  width: 1px;
  height: 14px;
  background: #e2e8f0;
}

.knowledge-card {
  position: relative;
  min-width: 0;
  min-height: 218px;
  overflow: visible;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 4px 18px rgba(15, 23, 42, 0.035);
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
}

.knowledge-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.075);
  transform: translateY(-1px);
}

.knowledge-card-rail {
  position: absolute;
  inset: 14px auto 14px -1px;
  width: 3px;
  border-radius: 0 3px 3px 0;
}

.knowledge-menu {
  position: absolute;
  top: 38px;
  right: 0;
  width: 156px;
  padding: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.14);
}

.knowledge-menu button {
  display: flex;
  width: 100%;
  height: 36px;
  align-items: center;
  gap: 9px;
  border-radius: 6px;
  padding: 0 10px;
  color: #b64252;
  font-size: 13px;
  font-weight: 600;
  transition: background-color 160ms ease;
}

.knowledge-menu button:hover {
  background: #fff2f4;
}

.knowledge-toast {
  position: fixed;
  z-index: 60;
  top: 24px;
  right: 28px;
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  background: #f0fdf4;
  padding: 10px 14px;
  color: #166534;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.12);
  font-size: 13px;
  font-weight: 600;
}

.knowledge-toast-enter-active,
.knowledge-toast-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.knowledge-toast-enter-from,
.knowledge-toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

@media (prefers-reduced-motion: reduce) {
  .knowledge-card,
  .knowledge-card :deep(svg),
  .knowledge-toast-enter-active,
  .knowledge-toast-leave-active {
    transition: none;
  }
}

@media (max-width: 1280px) {
  .knowledge-toolbar {
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
    padding: 16px 0;
  }

  .knowledge-summary {
    justify-content: flex-start;
  }
}

@media (max-width: 1180px) {
  .knowledge-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
