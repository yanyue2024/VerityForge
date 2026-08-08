<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowLeft,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Download,
  FileQuestion,
  Play,
  Search,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import ErrorState from '@/components/ErrorState.vue'
import { api, ApiClientError, getStoredAccessToken, readableError, resolveApiUrl } from '@/lib/api'
import { formatDate } from '@/lib/format'
import type { EvaluationCase, EvaluationDatasetDetail } from '@/types/api'

const route = useRoute()
const datasetId = computed(() => String(route.params.datasetId))
const search = ref('')
const page = ref(1)
const pageSize = 25
const exportError = ref('')
const exporting = ref(false)

const detailQuery = useQuery({
  queryKey: ['evaluation-dataset', datasetId],
  queryFn: () => api.get<EvaluationDatasetDetail>(`/api/v1/evaluation/datasets/${datasetId.value}`),
})

const filteredCases = computed(() => {
  const term = search.value.trim().toLowerCase()
  if (!term) return detailQuery.data.value?.cases ?? []
  return (detailQuery.data.value?.cases ?? []).filter((item) =>
    `${item.question} ${item.expectedAnswer ?? ''} ${metadataLabel(item)}`.toLowerCase().includes(term),
  )
})

const pageCount = computed(() => Math.max(1, Math.ceil(filteredCases.value.length / pageSize)))
const visibleCases = computed(() => {
  const start = (page.value - 1) * pageSize
  return filteredCases.value.slice(start, start + pageSize)
})
const rangeStart = computed(() => filteredCases.value.length ? (page.value - 1) * pageSize + 1 : 0)
const rangeEnd = computed(() => Math.min(page.value * pageSize, filteredCases.value.length))

watch(search, () => { page.value = 1 })
watch(pageCount, (count) => { if (page.value > count) page.value = count })

function metadataLabel(item: EvaluationCase) {
  return String(
    item.metadata.challengeType
      ?? item.metadata.challenge_type
      ?? item.metadata.sourceProject
      ?? item.metadata.source_project
      ?? '',
  )
}

async function downloadDataset() {
  exportError.value = ''
  exporting.value = true
  try {
    const headers = new Headers()
    const token = getStoredAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(resolveApiUrl(`/api/v1/evaluation/datasets/${datasetId.value}/export`), { headers })
    if (!response.ok) throw new ApiClientError('数据集导出失败', response.status)
    const url = URL.createObjectURL(await response.blob())
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `evaluation-${datasetId.value}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    exportError.value = readableError(error)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1280px] px-10 py-8">
    <RouterLink to="/evaluation" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950">
      <ArrowLeft :size="16" aria-hidden="true" />评测
    </RouterLink>

    <div v-if="detailQuery.isPending.value" class="mt-8 h-36 animate-pulse bg-paper-100" />
    <ErrorState v-else-if="detailQuery.isError.value" class="mt-8" :message="readableError(detailQuery.error.value)" @retry="detailQuery.refetch()" />

    <template v-else-if="detailQuery.data.value">
      <header class="mt-5 flex items-start justify-between gap-8">
        <div class="min-w-0">
          <p class="text-xs font-semibold uppercase text-brand-700">Evaluation dataset</p>
          <h1 class="mt-2 truncate text-[26px] font-semibold leading-tight text-ink-950">{{ detailQuery.data.value.dataset.name }}</h1>
          <p class="mt-2 max-w-3xl text-sm leading-6 text-ink-500">{{ detailQuery.data.value.dataset.description || '未填写说明' }}</p>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <button type="button" class="button-secondary" :disabled="exporting" @click="downloadDataset">
            <Download :size="16" aria-hidden="true" />导出 JSON
          </button>
          <RouterLink :to="`/evaluation/new?dataset=${datasetId}`" class="button-primary">
            <Play :size="16" aria-hidden="true" />使用此数据集
          </RouterLink>
        </div>
      </header>

      <div class="mt-7 grid grid-cols-3 border-y border-paper-200 py-5">
        <div><p class="text-xs text-ink-400">问题</p><p class="mt-1 text-xl font-semibold tabular-nums text-ink-950">{{ detailQuery.data.value.dataset.caseCount }}</p></div>
        <div class="border-l border-paper-200 pl-6"><p class="text-xs text-ink-400">历史任务</p><p class="mt-1 text-xl font-semibold tabular-nums text-ink-950">{{ detailQuery.data.value.dataset.runCount }}</p></div>
        <div class="border-l border-paper-200 pl-6"><p class="text-xs text-ink-400">创建时间</p><p class="mt-2 text-sm font-medium text-ink-800">{{ formatDate(detailQuery.data.value.dataset.createdAt) }}</p></div>
      </div>

      <p v-if="exportError" class="mt-4 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">{{ exportError }}</p>

      <div class="mt-6 flex h-12 items-center justify-between border-b border-paper-200">
        <div class="relative w-96">
          <Search :size="16" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" />
          <input v-model="search" class="control h-9 pl-9" placeholder="搜索问题或标准答案" />
        </div>
        <span class="text-xs tabular-nums text-ink-400">{{ filteredCases.length }} 条</span>
      </div>

      <div v-if="!visibleCases.length" class="flex min-h-56 flex-col items-center justify-center text-center">
        <FileQuestion :size="24" class="text-ink-300" aria-hidden="true" />
        <p class="mt-3 text-sm font-medium text-ink-700">没有符合条件的问题</p>
      </div>

      <div v-else class="divide-y divide-paper-200">
        <details v-for="item in visibleCases" :key="item.id" class="group">
          <summary class="grid min-h-[74px] cursor-pointer list-none grid-cols-[52px_minmax(0,1fr)_120px_32px] items-center gap-5 px-3 transition-colors hover:bg-white">
            <span class="text-xs tabular-nums text-ink-400">{{ String(item.position).padStart(3, '0') }}</span>
            <div class="min-w-0">
              <p class="truncate text-sm font-medium text-ink-900">{{ item.question }}</p>
              <p v-if="metadataLabel(item)" class="mt-1 truncate text-xs text-ink-400">{{ metadataLabel(item) }}</p>
            </div>
            <span class="text-xs text-ink-500">{{ item.expectedDocumentIds.length }} 个预期文档</span>
            <ChevronDown :size="17" class="text-ink-400 transition-transform group-open:rotate-180" aria-hidden="true" />
          </summary>
          <div class="grid grid-cols-[52px_minmax(0,1fr)_240px] gap-5 bg-paper-50 px-3 pb-6 pt-1">
            <span />
            <div>
              <p class="text-xs font-semibold text-ink-500">标准答案</p>
              <p class="mt-2 whitespace-pre-wrap text-sm leading-7 text-ink-800">{{ item.expectedAnswer || '未设置标准答案' }}</p>
            </div>
            <div>
              <p class="text-xs font-semibold text-ink-500">预期文档</p>
              <p v-for="documentId in item.expectedDocumentIds" :key="documentId" class="mt-2 break-all font-mono text-[11px] leading-5 text-ink-500">{{ documentId }}</p>
            </div>
          </div>
        </details>
      </div>

      <footer v-if="filteredCases.length > pageSize" class="flex h-16 items-center justify-between border-t border-paper-200">
        <span class="text-xs tabular-nums text-ink-400">{{ rangeStart }}–{{ rangeEnd }} / {{ filteredCases.length }}</span>
        <div class="flex items-center gap-2">
          <button type="button" class="icon-button size-8" title="上一页" :disabled="page <= 1" @click="page--"><ChevronLeft :size="16" /></button>
          <span class="min-w-16 text-center text-xs tabular-nums text-ink-600">{{ page }} / {{ pageCount }}</span>
          <button type="button" class="icon-button size-8" title="下一页" :disabled="page >= pageCount" @click="page++"><ChevronRight :size="16" /></button>
        </div>
      </footer>
    </template>
  </div>
</template>
