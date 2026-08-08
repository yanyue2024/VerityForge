<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { ArrowLeft, Check, Database, Download, FileSpreadsheet, LoaderCircle, Network, Route, Upload, Zap } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import MetadataFilterBuilder from '@/components/MetadataFilterBuilder.vue'
import { api, ApiClientError, getStoredAccessToken, readableError, resolveApiUrl } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import type {
  CreateRunRequest,
  EvaluationDataset,
  EvaluationDatasetDetail,
  EvaluationQueryPreview,
  EvaluationRun,
  KnowledgeBase,
  RunMode,
} from '@/types/api'

type DatasetSource = 'existing' | 'upload'

const route = useRoute()
const router = useRouter()
const file = ref<File | null>(null)
const datasetSource = ref<DatasetSource>(typeof route.query.dataset === 'string' ? 'existing' : 'upload')
const selectedDatasetId = ref(typeof route.query.dataset === 'string' ? route.query.dataset : '')
const mode = ref<RunMode>('AUTO')
const selectedKnowledgeBaseIds = ref<string[]>([])
const metadataFilters = ref<CreateRunRequest['filters']>([])
const preview = ref<EvaluationQueryPreview | null>(null)
const parsing = ref(false)
const actionError = ref('')
let parseSequence = 0

const knowledgeQuery = useQuery({
  queryKey: ['knowledge-bases'],
  queryFn: () => api.get<KnowledgeBase[]>('/api/v1/knowledge-bases'),
})

const datasetsQuery = useQuery({
  queryKey: ['evaluation-datasets'],
  queryFn: () => api.get<EvaluationDataset[]>('/api/v1/evaluation/datasets'),
})

const selectedDataset = computed(() =>
  datasetsQuery.data.value?.find((dataset) => dataset.id === selectedDatasetId.value),
)

const modeOptions = [
  { value: 'AUTO' as const, label: '自动', description: '按问题复杂度选择运行方式', icon: Route },
  { value: 'FAST' as const, label: '快速', description: '固定使用快速检索链路', icon: Zap },
  { value: 'DEEP' as const, label: '深度', description: '固定使用多轮检索与证据核对', icon: Network },
]

const previewErrors = computed(() => [
  ...(preview.value?.errors ?? []),
  ...(preview.value?.rows.flatMap((row) => row.errors.map((error) => `第 ${row.rowNumber} 行：${error}`)) ?? []),
])
const validCaseCount = computed(() => datasetSource.value === 'existing'
  ? selectedDataset.value?.caseCount ?? 0
  : preview.value?.bundle.cases.length ?? 0)
const canSubmit = computed(() => Boolean(
  selectedKnowledgeBaseIds.value.length
  && (datasetSource.value === 'existing'
    ? selectedDataset.value
    : file.value && preview.value && !previewErrors.value.length && validCaseCount.value),
))

async function parseFile() {
  if (datasetSource.value !== 'upload' || !file.value || !selectedKnowledgeBaseIds.value.length) {
    preview.value = null
    return
  }
  const sequence = ++parseSequence
  parsing.value = true
  actionError.value = ''
  try {
    const body = new FormData()
    body.append('file', file.value)
    const params = selectedKnowledgeBaseIds.value.map((id) => `knowledgeBaseId=${encodeURIComponent(id)}`).join('&')
    const result = await api.post<EvaluationQueryPreview>(`/api/v1/evaluation/query-files/parse?${params}`, body)
    if (sequence === parseSequence) preview.value = result
  } catch (error) {
    if (sequence === parseSequence) {
      preview.value = null
      actionError.value = readableError(error)
    }
  } finally {
    if (sequence === parseSequence) parsing.value = false
  }
}

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  preview.value = null
  void parseFile()
}

watch(selectedKnowledgeBaseIds, () => {
  if (datasetSource.value === 'upload') {
    preview.value = null
    void parseFile()
  }
}, { deep: true })

watch(datasetSource, (source) => {
  actionError.value = ''
  if (source === 'upload') void parseFile()
})

watch(
  () => datasetsQuery.data.value,
  (datasets) => {
    const requested = typeof route.query.dataset === 'string' ? route.query.dataset : ''
    if (requested && datasets?.some((dataset) => dataset.id === requested)) {
      datasetSource.value = 'existing'
      selectedDatasetId.value = requested
    }
  },
  { immediate: true },
)

const createMutation = useMutation({
  mutationFn: async () => {
    let datasetId = selectedDatasetId.value
    if (datasetSource.value === 'upload') {
      if (!preview.value) throw new Error('Query XLSX 尚未通过校验')
      const dataset = await api.post<EvaluationDatasetDetail>('/api/v1/evaluation/datasets/import', preview.value.bundle)
      datasetId = dataset.dataset.id
    }
    if (!datasetId) throw new Error('请选择评测数据集')
    return api.post<EvaluationRun>(`/api/v1/evaluation/datasets/${datasetId}/runs`, {
      mode: mode.value,
      scope: { knowledgeBaseIds: selectedKnowledgeBaseIds.value, documentIds: [] },
      filters: metadataFilters.value,
      judgeMode: 'ANSWER_AND_CITATIONS',
    })
  },
  onSuccess: (run) => router.replace(`/evaluation/runs/${run.id}`),
  onError: (error) => { actionError.value = readableError(error) },
})

async function downloadTemplate() {
  actionError.value = ''
  try {
    const headers = new Headers()
    const token = getStoredAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(resolveApiUrl('/api/v1/evaluation/query-files/template'), { headers })
    if (!response.ok) throw new ApiClientError('Query 模板下载失败', response.status)
    const url = URL.createObjectURL(await response.blob())
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'evaluation-query-template.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    actionError.value = readableError(error)
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1040px] px-10 py-8">
    <RouterLink to="/evaluation" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950"><ArrowLeft :size="16" aria-hidden="true" />评测</RouterLink>
    <header class="mt-5 border-b border-paper-200 pb-7"><h1 class="text-[28px] font-semibold leading-tight text-ink-950">新建评测</h1><p class="mt-2 text-sm text-ink-500">每个任务只运行一种模式；执行成功与质量得分分别统计。</p></header>

    <form class="divide-y divide-paper-200" @submit.prevent="createMutation.mutate()">
      <section class="grid grid-cols-[180px_1fr] gap-8 py-7">
        <div><p class="text-sm font-semibold text-ink-950">1. 评测数据</p><p class="mt-1 text-xs leading-5 text-ink-500">复用数据集或上传 XLSX</p></div>
        <div>
          <div class="inline-flex rounded-md bg-paper-100 p-0.5">
            <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md px-3 text-xs font-semibold" :class="datasetSource === 'existing' ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500 hover:text-ink-900'" @click="datasetSource = 'existing'"><Database :size="14" />已有数据集</button>
            <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md px-3 text-xs font-semibold" :class="datasetSource === 'upload' ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500 hover:text-ink-900'" @click="datasetSource = 'upload'"><Upload :size="14" />上传 XLSX</button>
          </div>

          <div v-if="datasetSource === 'existing'" class="mt-4">
            <div v-if="datasetsQuery.isPending.value" class="h-20 animate-pulse bg-paper-100" />
            <div v-else-if="datasetsQuery.data.value?.length" class="divide-y divide-paper-200 border-y border-paper-200">
              <button v-for="dataset in datasetsQuery.data.value" :key="dataset.id" type="button" class="grid min-h-[68px] w-full grid-cols-[36px_minmax(0,1fr)_110px_24px] items-center gap-3 px-3 text-left transition-colors hover:bg-white" :class="selectedDatasetId === dataset.id ? 'bg-brand-50' : ''" @click="selectedDatasetId = dataset.id">
                <span class="flex size-8 items-center justify-center rounded-md" :class="selectedDatasetId === dataset.id ? 'bg-brand-100 text-brand-700' : 'bg-paper-100 text-ink-500'"><Database :size="16" /></span>
                <span class="min-w-0"><span class="block truncate text-sm font-semibold text-ink-900">{{ dataset.name }}</span><span class="mt-1 block truncate text-xs text-ink-400">{{ dataset.description || '未填写说明' }}</span></span>
                <span class="text-right text-xs tabular-nums text-ink-500">{{ dataset.caseCount }} 个问题</span>
                <span class="flex size-5 items-center justify-center rounded-full border" :class="selectedDatasetId === dataset.id ? 'border-brand-600 bg-brand-600 text-white' : 'border-paper-300'"><Check v-if="selectedDatasetId === dataset.id" :size="12" stroke-width="3" /></span>
              </button>
            </div>
            <p v-else class="border-y border-paper-200 py-7 text-center text-sm text-ink-500">暂无可复用数据集</p>
          </div>

          <div v-else class="mt-4">
            <label class="flex min-h-28 cursor-pointer items-center gap-4 rounded-lg border border-dashed border-paper-300 bg-white px-5 hover:border-brand-200 hover:bg-brand-50">
              <span class="flex size-10 items-center justify-center rounded-md bg-evidence-50 text-evidence-700"><FileSpreadsheet :size="20" aria-hidden="true" /></span>
              <span class="min-w-0 flex-1"><span class="block truncate text-sm font-semibold text-ink-900">{{ file?.name || '选择 Query XLSX' }}</span><span class="mt-1 block text-xs text-ink-500">{{ file ? formatBytes(file.size) : '包含 query，以及标准答案、预期文档或无答案预期' }}</span></span>
              <input class="sr-only" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="onFileChange" />
            </label>
            <button type="button" class="mt-3 inline-flex h-8 items-center gap-1.5 text-xs font-semibold text-brand-700" @click="downloadTemplate"><Download :size="14" aria-hidden="true" />下载 Query 模板</button>
          </div>
        </div>
      </section>

      <section class="grid grid-cols-[180px_1fr] gap-8 py-7">
        <div><p class="text-sm font-semibold text-ink-950">2. 运行模式</p><p class="mt-1 text-xs leading-5 text-ink-500">必选，作用于全部样例</p></div>
        <div class="grid grid-cols-3 gap-3">
          <button v-for="option in modeOptions" :key="option.value" type="button" class="relative min-h-24 rounded-lg border p-4 text-left transition-colors" :class="mode === option.value ? 'border-brand-600 bg-brand-50' : 'border-paper-200 bg-white hover:border-paper-300'" @click="mode = option.value">
            <component :is="option.icon" :size="18" :class="mode === option.value ? 'text-brand-700' : 'text-ink-500'" aria-hidden="true" />
            <p class="mt-3 text-sm font-semibold text-ink-950">{{ option.label }}</p><p class="mt-1 text-xs leading-5 text-ink-500">{{ option.description }}</p>
            <span v-if="mode === option.value" class="absolute right-3 top-3 flex size-5 items-center justify-center rounded-full bg-brand-600 text-white"><Check :size="12" stroke-width="3" /></span>
          </button>
        </div>
      </section>

      <section class="grid grid-cols-[180px_1fr] gap-8 py-7">
        <div><p class="text-sm font-semibold text-ink-950">3. 知识范围</p><p class="mt-1 text-xs leading-5 text-ink-500">至少选择一个知识库</p></div>
        <div>
          <div v-if="knowledgeQuery.isPending.value" class="h-20 animate-pulse bg-paper-100" />
          <div v-else class="grid grid-cols-2 gap-2">
            <label v-for="knowledge in knowledgeQuery.data.value" :key="knowledge.id" class="flex min-h-12 cursor-pointer items-center gap-3 rounded-lg border px-3" :class="selectedKnowledgeBaseIds.includes(knowledge.id) ? 'border-brand-200 bg-brand-50' : 'border-paper-200 bg-white hover:border-paper-300'">
              <span class="relative flex size-4 items-center justify-center rounded border" :class="selectedKnowledgeBaseIds.includes(knowledge.id) ? 'border-brand-600 bg-brand-600 text-white' : 'border-paper-300'"><Check v-if="selectedKnowledgeBaseIds.includes(knowledge.id)" :size="12" stroke-width="3" /><input v-model="selectedKnowledgeBaseIds" type="checkbox" :value="knowledge.id" class="absolute inset-0 opacity-0" /></span>
              <span class="min-w-0 flex-1 truncate text-sm font-medium text-ink-800">{{ knowledge.name }}</span><span class="text-xs text-ink-400">{{ knowledge.documentCount }} 文档</span>
            </label>
          </div>
          <div class="mt-3 flex items-center gap-2"><MetadataFilterBuilder v-model="metadataFilters" :knowledge-base-ids="selectedKnowledgeBaseIds" /><span class="text-xs text-ink-400">可选，进一步限定参与评测的文档</span></div>
        </div>
      </section>

      <section class="grid grid-cols-[180px_1fr] gap-8 py-7">
        <div><p class="text-sm font-semibold text-ink-950">4. 校验结果</p><p class="mt-1 text-xs leading-5 text-ink-500">提交前检查样例与文档映射</p></div>
        <div>
          <div v-if="datasetSource === 'existing' && selectedDataset" class="grid min-h-20 grid-cols-[minmax(0,1fr)_120px_120px] items-center gap-4 border-y border-paper-200 px-3">
            <div class="min-w-0"><p class="truncate text-sm font-semibold text-ink-900">{{ selectedDataset.name }}</p><p class="mt-1 text-xs text-evidence-700">数据集可用</p></div>
            <div><p class="text-xs text-ink-400">问题</p><p class="mt-1 text-sm font-semibold tabular-nums text-ink-800">{{ selectedDataset.caseCount }}</p></div>
            <div><p class="text-xs text-ink-400">历史任务</p><p class="mt-1 text-sm font-semibold tabular-nums text-ink-800">{{ selectedDataset.runCount }}</p></div>
          </div>
          <p v-else-if="datasetSource === 'existing'" class="rounded-lg border border-paper-200 px-4 py-6 text-center text-sm text-ink-500">请选择一个数据集。</p>
          <div v-else-if="parsing" class="flex h-20 items-center justify-center gap-2 rounded-lg border border-paper-200 text-sm text-ink-500"><LoaderCircle :size="16" class="animate-spin" />正在校验 Query XLSX</div>
          <div v-else-if="preview" class="overflow-hidden rounded-lg border border-paper-200">
            <div class="flex items-center justify-between bg-paper-100 px-4 py-3"><div><p class="text-sm font-semibold text-ink-900">{{ preview.suggestedName }}</p><p class="mt-1 text-xs text-ink-500">{{ validCaseCount }} 个有效样例</p></div><span class="text-xs font-semibold" :class="previewErrors.length ? 'text-coral-700' : 'text-evidence-700'">{{ previewErrors.length ? `${previewErrors.length} 个问题` : '校验通过' }}</span></div>
            <div class="max-h-56 overflow-y-auto scrollbar-subtle"><div v-for="row in preview.rows" :key="row.rowNumber" class="grid min-h-12 grid-cols-[52px_1fr_100px_1fr] items-center gap-3 border-t border-paper-200 px-4 text-xs"><span class="text-ink-400">{{ row.rowNumber }}</span><span class="truncate text-ink-800">{{ row.question }}</span><span class="text-ink-500">{{ row.expectedDocumentCount }} 个预期文档</span><span :class="row.errors.length ? 'text-coral-700' : 'text-evidence-700'">{{ row.errors.join('；') || '可评测' }}</span></div></div>
          </div>
          <p v-else class="rounded-lg border border-paper-200 px-4 py-6 text-center text-sm text-ink-500">选择知识库和 Query XLSX 后显示校验结果。</p>
          <div v-if="datasetSource === 'upload' && previewErrors.length" class="mt-3 rounded-md bg-coral-50 px-4 py-3 text-xs leading-6 text-coral-700"><p v-for="error in previewErrors.slice(0, 8)" :key="error">{{ error }}</p><p v-if="previewErrors.length > 8">其余 {{ previewErrors.length - 8 }} 个问题请在表格中修正。</p></div>
        </div>
      </section>

      <div v-if="actionError" class="rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">{{ actionError }}</div>
      <div class="flex items-center justify-end gap-3 py-6"><RouterLink to="/evaluation" class="button-secondary">取消</RouterLink><button type="submit" class="button-primary" :disabled="!canSubmit || createMutation.isPending.value"><LoaderCircle v-if="createMutation.isPending.value" :size="17" class="animate-spin" />创建并运行</button></div>
    </form>
  </div>
</template>
