<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowRight,
  BarChart3,
  CheckCircle2,
  CircleAlert,
  Clock3,
  Files,
  FlaskConical,
  Gauge,
  Network,
  Plus,
  Search,
  Zap,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import type { EvaluationDataset, EvaluationRunDetail, EvaluationRunSummary } from '@/types/api'

type RunFilter = 'ALL' | 'ACTIVE' | 'COMPLETED' | 'FAILED'
type EvaluationView = 'runs' | 'datasets'

const search = ref('')
const filter = ref<RunFilter>('ALL')
const activeView = ref<EvaluationView>('runs')

const runsQuery = useQuery({
  queryKey: ['evaluation-runs'],
  queryFn: () => api.get<EvaluationRunSummary[]>('/api/v1/evaluation/runs?limit=100'),
  refetchInterval: (query: { state: { data?: EvaluationRunSummary[] } }) =>
    query.state.data?.some((run) => ['QUEUED', 'RUNNING'].includes(run.status)) ? 2_000 : false,
})

const datasetsQuery = useQuery({
  queryKey: ['evaluation-datasets'],
  queryFn: () => api.get<EvaluationDataset[]>('/api/v1/evaluation/datasets'),
})

const completedRunDetailsQuery = useQuery(computed(() => {
  const runs = runsQuery.data.value ?? []
  return {
    queryKey: ['evaluation-run-details', runs.map((run) => run.id).join(',')],
    enabled: Boolean(runs.length),
    queryFn: () => Promise.all(runs.slice(0, 24).map((run) => api.get<EvaluationRunDetail>(`/api/v1/evaluation/runs/${run.id}`))),
  }
}))

const filteredRuns = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (runsQuery.data.value ?? []).filter((run) => {
    if (term && !`${run.name} ${run.datasetName}`.toLowerCase().includes(term)) return false
    if (filter.value === 'ACTIVE') return ['QUEUED', 'RUNNING'].includes(run.status)
    if (filter.value === 'COMPLETED') return run.status === 'COMPLETED'
    if (filter.value === 'FAILED') return ['FAILED', 'CANCELLED'].includes(run.status) || run.failedCases > 0
    return true
  })
})

const filteredDatasets = computed(() => {
  const term = search.value.trim().toLowerCase()
  if (!term) return datasetsQuery.data.value ?? []
  return (datasetsQuery.data.value ?? []).filter((dataset) => `${dataset.name} ${dataset.description}`.toLowerCase().includes(term))
})

const detailsById = computed(() => new Map((completedRunDetailsQuery.data.value ?? []).map((detail) => [detail.run.id, detail])))
const v8Runs = computed(() => {
  const runs = (runsQuery.data.value ?? []).filter((run) => run.datasetName.includes('三策略'))
  return runs
    .map((run) => detailsById.value.get(run.id))
    .filter((detail): detail is EvaluationRunDetail => Boolean(detail))
    .sort((a, b) => String(a.requestSnapshot.mode).localeCompare(String(b.requestSnapshot.mode)))
})
const v8Dataset = computed(() => datasetsQuery.data.value?.find((dataset) => dataset.name.includes('三策略')))
const v8Fast = computed(() => v8Runs.value.find((detail) => String(detail.requestSnapshot.mode) === 'FAST'))
const v8Deep = computed(() => v8Runs.value.find((detail) => String(detail.requestSnapshot.mode) === 'DEEP'))
const v8Ready = computed(() => Boolean(v8Fast.value && v8Deep.value))

const totals = computed(() => ({
  all: runsQuery.data.value?.length ?? 0,
  active: runsQuery.data.value?.filter((run) => ['QUEUED', 'RUNNING'].includes(run.status)).length ?? 0,
  completed: runsQuery.data.value?.filter((run) => run.status === 'COMPLETED').length ?? 0,
  failed: runsQuery.data.value?.filter((run) => ['FAILED', 'CANCELLED'].includes(run.status) || run.failedCases > 0).length ?? 0,
}))

const comparisonRows = [
  { key: 'success', label: '链路成功率', note: '得到最终答案的样例', format: 'percent' },
  { key: 'recallAt5', label: 'Recall@5', note: '预期文档进入前五', format: 'percent' },
  { key: 'acceptedEvidenceCoverage', label: 'AEC', note: 'Accepted Evidence 覆盖', format: 'percent' },
  { key: 'researchContextCoverage', label: 'RCC', note: '最终研究上下文覆盖', format: 'percent' },
  { key: 'semanticAnswerScore', label: '答案语义', note: '模型判定的回答质量', format: 'percent' },
  { key: 'citationEntailmentScore', label: '引用支持', note: '引用是否支撑答案', format: 'percent' },
  { key: 'averageLatencyMs', label: '平均耗时', note: '完整评测单题平均', format: 'duration' },
  { key: 'totalTokens', label: '总模型 Token', note: '运行记录中的总量', format: 'tokens' },
]

function numberValue(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}
function metric(detail: EvaluationRunDetail | undefined, key: string) {
  if (!detail) return null
  if (key === 'acceptedEvidenceCoverage' && String(detail.requestSnapshot.mode) === 'FAST') return null
  if (key === 'success') {
    const total = numberValue(detail.run.aggregateMetrics.caseCount) || detail.dataset.caseCount
    const successful = numberValue(detail.run.aggregateMetrics.successfulCases)
    return total ? successful / total : null
  }
  const value = detail.run.aggregateMetrics[key]
  return value === undefined || value === null ? null : numberValue(value)
}
function display(detail: EvaluationRunDetail | undefined, row: (typeof comparisonRows)[number]) {
  const value = metric(detail, row.key)
  if (value === null) return '—'
  if (row.format === 'percent') return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`
  if (row.format === 'duration') return value >= 1000 ? `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)}s` : `${Math.round(value)}ms`
  return value ? Math.round(value).toLocaleString() : '未记录'
}
function barValue(detail: EvaluationRunDetail | undefined, row: (typeof comparisonRows)[number]) {
  const value = metric(detail, row.key)
  if (value === null || row.format !== 'percent') return 0
  return Math.round(Math.max(0, Math.min(1, value)) * 100)
}
function progress(run: EvaluationRunSummary) {
  return run.totalCases ? Math.round(run.completedCases / run.totalCases * 100) : 0
}
function modeIcon(mode: string) { return mode === 'DEEP' ? Network : Zap }
</script>

<template>
  <div class="evaluation-page mx-auto w-full max-w-[1380px] px-10 py-8">
    <header class="flex items-start justify-between gap-8">
      <div>
        <p class="section-label">Evaluation lab</p>
        <h1 class="page-title mt-2">评测</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink-500">把链路是否跑通、检索是否找对、答案是否可信分开看，快速定位下一步该优化什么。</p>
      </div>
      <RouterLink to="/evaluation/new" class="button-primary"><Plus :size="17" aria-hidden="true" />新建评测</RouterLink>
    </header>

    <section v-if="v8Ready" class="evaluation-hero mt-8 overflow-hidden rounded-xl border border-brand-100 bg-white">
      <div class="flex items-start justify-between gap-8 border-b border-paper-200 px-7 py-6">
        <div>
          <div class="flex items-center gap-3"><span class="benchmark-stamp">V8 FINAL</span><span class="text-xs font-medium text-ink-400">已导入 · {{ v8Dataset?.caseCount ?? 5 }} 个样例</span></div>
          <h2 class="mt-3 text-xl font-semibold text-ink-950">Fast / Deep 完整链路对照</h2>
          <p class="mt-1 text-sm text-ink-500">同一数据集、同一知识范围、同一模型配置；蓝色强调质量，墨色强调成本。</p>
        </div>
        <div class="flex items-center gap-2 text-xs text-ink-500"><span class="inline-flex items-center gap-1.5"><span class="legend-dot bg-brand-600" />Deep</span><span class="inline-flex items-center gap-1.5"><span class="legend-dot bg-ink-400" />Fast</span></div>
      </div>
      <div class="grid grid-cols-[minmax(0,1fr)_160px_160px] border-b border-paper-200 bg-paper-50 px-7 py-3 text-xs font-semibold text-ink-400"><span>指标</span><span class="text-right text-ink-500">Fast</span><span class="text-right text-brand-700">Deep</span></div>
      <div class="divide-y divide-paper-100 px-7">
        <div v-for="row in comparisonRows" :key="row.key" class="grid min-h-[68px] grid-cols-[minmax(0,1fr)_160px_160px] items-center gap-6">
          <div><p class="text-sm font-semibold text-ink-900">{{ row.label }}</p><p class="mt-1 text-xs text-ink-400">{{ row.note }}</p></div>
          <div class="text-right"><p class="text-sm font-semibold tabular-nums text-ink-700">{{ display(v8Fast, row) }}</p><div class="metric-track ml-auto mt-2"><span class="bg-ink-300" :style="{ width: `${barValue(v8Fast, row)}%` }" /></div></div>
          <div class="text-right"><p class="text-sm font-semibold tabular-nums text-brand-700">{{ display(v8Deep, row) }}</p><div class="metric-track ml-auto mt-2"><span class="bg-brand-600" :style="{ width: `${barValue(v8Deep, row)}%` }" /></div></div>
        </div>
      </div>
      <div class="flex items-center justify-between gap-5 px-7 py-5"><div class="flex items-center gap-5 text-xs text-ink-500"><span class="inline-flex items-center gap-1.5"><CheckCircle2 :size="15" class="text-evidence-600" />两条链路均完成</span><span class="inline-flex items-center gap-1.5"><Gauge :size="15" class="text-brand-600" />Deep 质量优先</span><span class="inline-flex items-center gap-1.5"><Clock3 :size="15" class="text-ink-400" />Fast 更快</span></div><div class="flex items-center gap-2"><RouterLink v-if="v8Fast" :to="`/evaluation/runs/${v8Fast.run.id}`" class="button-secondary">查看 Fast</RouterLink><RouterLink v-if="v8Deep" :to="`/evaluation/runs/${v8Deep.run.id}`" class="button-primary">查看 Deep<ArrowRight :size="16" /></RouterLink></div></div>
    </section>
    <section v-else-if="completedRunDetailsQuery.isFetching.value" class="evaluation-hero mt-8 h-[360px] animate-pulse rounded-xl border border-paper-200 bg-white" />

    <nav class="mt-10 flex h-12 items-end gap-7 border-b border-paper-200" aria-label="评测内容">
      <button type="button" class="relative h-12 text-sm font-semibold" :class="activeView === 'runs' ? 'text-ink-950' : 'text-ink-500 hover:text-ink-900'" @click="activeView = 'runs'; search = ''"><BarChart3 :size="16" class="mr-2 inline" />评测任务 <span class="ml-1 text-xs tabular-nums text-ink-400">{{ runsQuery.data.value?.length ?? 0 }}</span><span v-if="activeView === 'runs'" class="absolute inset-x-0 bottom-0 h-0.5 bg-ink-950" /></button>
      <button type="button" class="relative h-12 text-sm font-semibold" :class="activeView === 'datasets' ? 'text-ink-950' : 'text-ink-500 hover:text-ink-900'" @click="activeView = 'datasets'; search = ''"><Files :size="16" class="mr-2 inline" />数据集 <span class="ml-1 text-xs tabular-nums text-ink-400">{{ datasetsQuery.data.value?.length ?? 0 }}</span><span v-if="activeView === 'datasets'" class="absolute inset-x-0 bottom-0 h-0.5 bg-ink-950" /></button>
    </nav>

    <div class="flex min-h-16 items-center gap-5 border-b border-paper-200">
      <div class="relative w-80"><Search :size="16" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" /><input v-model="search" class="control h-10 pl-9" :placeholder="activeView === 'runs' ? '搜索任务或数据集' : '搜索数据集'" /></div>
      <div v-if="activeView === 'runs'" class="inline-flex rounded-lg bg-paper-100 p-0.5"><button v-for="option in ([['ALL', `全部 ${totals.all}`], ['ACTIVE', `进行中 ${totals.active}`], ['COMPLETED', `已完成 ${totals.completed}`], ['FAILED', `有异常 ${totals.failed}`]] as const)" :key="option[0]" type="button" class="h-8 rounded-md px-3 text-xs font-semibold" :class="filter === option[0] ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500 hover:text-ink-900'" @click="filter = option[0]">{{ option[1] }}</button></div>
    </div>

    <template v-if="activeView === 'runs'">
      <div v-if="runsQuery.isPending.value" class="divide-y divide-paper-200"><div v-for="item in 6" :key="item" class="h-20 animate-pulse bg-paper-100" /></div>
      <ErrorState v-else-if="runsQuery.isError.value" class="mt-7" :message="readableError(runsQuery.error.value)" @retry="runsQuery.refetch()" />
      <EmptyState v-else-if="!filteredRuns.length" class="mt-10" :icon="FlaskConical" :title="search || filter !== 'ALL' ? '没有符合条件的评测' : '还没有评测任务'" :description="search || filter !== 'ALL' ? '调整搜索或状态条件。' : '从已有数据集或 Query XLSX 创建第一次评测。'"><RouterLink v-if="!search && filter === 'ALL'" to="/evaluation/new" class="button-primary"><Plus :size="17" />新建评测</RouterLink></EmptyState>
      <div v-else class="evaluation-run-table">
        <div class="grid grid-cols-[minmax(320px,1fr)_96px_190px_112px_140px_28px] gap-5 border-b border-paper-200 px-3 py-3 text-xs font-medium text-ink-400"><span>任务</span><span>模式</span><span>进度</span><span>异常</span><span>创建时间</span><span /></div>
        <RouterLink v-for="run in filteredRuns" :key="run.id" :to="`/evaluation/runs/${run.id}`" class="group grid min-h-[82px] grid-cols-[minmax(320px,1fr)_96px_190px_112px_140px_28px] items-center gap-5 border-b border-paper-200 px-3 transition-colors hover:bg-white">
          <div class="min-w-0"><p class="truncate text-sm font-semibold text-ink-950 group-hover:text-brand-700">{{ run.name }}</p><div class="mt-1 flex items-center gap-2"><StatusPill :status="run.status" /><span class="truncate text-xs text-ink-400">{{ run.datasetName }}</span></div></div>
          <span class="inline-flex w-fit items-center gap-1.5 rounded-md bg-paper-100 px-2 py-1 text-xs font-semibold text-ink-700"><component :is="modeIcon(run.mode)" :size="13" />{{ run.mode }}</span>
          <div><div class="flex items-center justify-between text-xs text-ink-500"><span>{{ run.completedCases }} / {{ run.totalCases }}</span><span>{{ progress(run) }}%</span></div><div class="mt-2 h-1.5 overflow-hidden rounded-full bg-paper-200"><div class="h-full rounded-full" :class="run.failedCases ? 'bg-amber-600' : 'bg-brand-600'" :style="{ width: `${progress(run)}%` }" /></div></div>
          <span class="inline-flex items-center gap-1.5 text-sm font-semibold tabular-nums" :class="run.failedCases ? 'text-coral-700' : 'text-ink-500'"><CircleAlert v-if="run.failedCases" :size="14" />{{ run.failedCases }}</span><span class="text-xs text-ink-500">{{ formatDate(run.createdAt) }}</span><ArrowRight :size="17" class="text-ink-400 transition-transform group-hover:translate-x-0.5 group-hover:text-brand-700" aria-hidden="true" />
        </RouterLink>
      </div>
    </template>
    <template v-else>
      <div v-if="datasetsQuery.isPending.value" class="divide-y divide-paper-200"><div v-for="item in 4" :key="item" class="h-20 animate-pulse bg-paper-100" /></div>
      <ErrorState v-else-if="datasetsQuery.isError.value" class="mt-7" :message="readableError(datasetsQuery.error.value)" @retry="datasetsQuery.refetch()" />
      <EmptyState v-else-if="!filteredDatasets.length" class="mt-10" :icon="Files" :title="search ? '没有符合条件的数据集' : '还没有评测数据集'" :description="search ? '调整搜索内容。' : '新建评测时上传 Query XLSX。'" />
      <div v-else class="evaluation-run-table"><div class="grid grid-cols-[minmax(360px,1fr)_120px_120px_140px_160px_32px] gap-5 border-b border-paper-200 px-3 py-3 text-xs font-medium text-ink-400"><span>数据集</span><span>问题</span><span>历史任务</span><span>最近状态</span><span>创建时间</span><span /></div><RouterLink v-for="dataset in filteredDatasets" :key="dataset.id" :to="`/evaluation/datasets/${dataset.id}`" class="group grid min-h-[82px] grid-cols-[minmax(360px,1fr)_120px_120px_140px_160px_32px] items-center gap-5 border-b border-paper-200 px-3 transition-colors hover:bg-white"><div class="min-w-0"><div class="flex items-center gap-2"><p class="truncate text-sm font-semibold text-ink-950 group-hover:text-brand-700">{{ dataset.name }}</p><span v-if="dataset.name.includes('三策略')" class="benchmark-stamp">V8 FINAL</span></div><p class="mt-1 truncate text-xs text-ink-400">{{ dataset.description || '未填写说明' }}</p></div><span class="text-sm font-semibold tabular-nums text-ink-800">{{ dataset.caseCount }}</span><span class="text-sm tabular-nums text-ink-600">{{ dataset.runCount }}</span><StatusPill v-if="dataset.lastRunStatus" :status="dataset.lastRunStatus" /><span v-else class="text-xs text-ink-400">尚未运行</span><span class="text-xs text-ink-500">{{ formatDate(dataset.createdAt) }}</span><ArrowRight :size="17" class="text-ink-400 transition-transform group-hover:translate-x-0.5 group-hover:text-brand-700" aria-hidden="true" /></RouterLink></div>
    </template>
  </div>
</template>
