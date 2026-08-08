<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  ArrowRight,
  Ban,
  CircleAlert,
  LoaderCircle,
  RefreshCw,
  RotateCcw,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import type { EvaluationResult, EvaluationRun, EvaluationRunDetail, RunMode } from '@/types/api'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const actionError = ref('')
const sampleFilter = ref<'ALL' | 'REVIEW' | 'FAILED'>('ALL')
const runId = computed(() => String(route.params.runId))

const runQuery = useQuery(
  computed(() => ({
    queryKey: ['evaluation-run', runId.value],
    queryFn: () => api.get<EvaluationRunDetail>(`/api/v1/evaluation/runs/${runId.value}`),
    refetchInterval: (query: { state: { data?: EvaluationRunDetail } }) =>
      ['QUEUED', 'RUNNING'].includes(query.state.data?.run.status ?? '') ? 2_000 : false,
  })),
)

const detail = computed(() => runQuery.data.value)
const run = computed(() => detail.value?.run)
const aggregate = computed(() => run.value?.aggregateMetrics ?? {})
const snapshot = computed(() => detail.value?.requestSnapshot ?? {})
const results = computed(() => detail.value?.results ?? [])
const totalCases = computed(() => metricNumber(aggregate.value, 'caseCount') || detail.value?.dataset.caseCount || 0)
const successfulCases = computed(() => metricNumber(aggregate.value, 'successfulCases') || results.value.filter((item) => !item.errorMessage).length)
const failedCases = computed(() => metricNumber(aggregate.value, 'failedCases') || results.value.filter((item) => item.errorMessage).length)
const completedCases = computed(() => Math.min(totalCases.value, Math.max(results.value.length, successfulCases.value + failedCases.value)))
const progress = computed(() => totalCases.value ? Math.round(completedCases.value / totalCases.value * 100) : 0)
const mode = computed<RunMode>(() => String(snapshot.value.mode ?? aggregate.value.requestedMode ?? 'AUTO') as RunMode)
const retrievalOnly = computed(() => String(snapshot.value.execution ?? aggregate.value.execution) === 'AGENTIC_RETRIEVAL_ONLY')
const isActive = computed(() => ['QUEUED', 'RUNNING'].includes(run.value?.status ?? ''))
const isTerminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.value?.status ?? ''))

const executionRate = computed(() => totalCases.value ? successfulCases.value / totalCases.value : 0)
const metricCards = computed(() => [
  { label: retrievalOnly.value ? '研究链路成功率' : '链路成功率', value: percent(executionRate.value), detail: `${successfulCases.value} / ${totalCases.value} 样例${retrievalOnly.value ? '完成检索与证据验收' : '得到最终答案'}`, tone: executionRate.value === 1 ? 'evidence' : 'coral' },
  { label: 'Recall@5', value: optionalPercent(aggregate.value.recallAt5), detail: `${metricNumber(aggregate.value, 'retrievalGradedCases')} 个样例包含预期文档`, tone: 'brand' },
  { label: 'AEC', value: mode.value === 'FAST' ? '—' : optionalPercent(aggregate.value.acceptedEvidenceCoverage), detail: mode.value === 'FAST' ? 'Fast 不生产 Accepted Evidence' : 'Accepted Evidence 覆盖标准答案', tone: 'brand' },
  { label: 'RCC', value: optionalPercent(aggregate.value.researchContextCoverage), detail: '最终研究上下文覆盖标准答案', tone: 'brand' },
  { label: '答案语义', value: metricNumber(aggregate.value, 'semanticAnswerJudgedCases') ? optionalPercent(aggregate.value.semanticAnswerScore) : '—', detail: `${metricNumber(aggregate.value, 'semanticAnswerJudgedCases')} 个样例参与判定`, tone: 'brand' },
  { label: '引用可解析', value: metricNumber(aggregate.value, 'citationGradedCases') ? optionalPercent(aggregate.value.citationResolvableRate) : '—', detail: `${metricNumber(aggregate.value, 'citationGradedCases')} 个样例生成引用`, tone: 'evidence' },
  { label: 'P95 延迟', value: formatLatency(metricNumber(aggregate.value, 'p95LatencyMs')), detail: `平均 ${formatLatency(metricNumber(aggregate.value, 'averageLatencyMs'))}`, tone: 'ink' },
  { label: '总模型 Token', value: metricNumber(aggregate.value, 'totalTokens') ? metricNumber(aggregate.value, 'totalTokens').toLocaleString() : '未记录', detail: '只统计当前任务', tone: 'ink' },
])

const issues = computed(() => {
  const values = [
    { label: '执行失败', count: results.value.filter((item) => Boolean(item.errorMessage)).length, tone: 'coral' },
    { label: '答案待复核', count: retrievalOnly.value ? 0 : results.value.filter((item) => !item.errorMessage && answerNeedsReview(item)).length, tone: 'amber' },
    { label: '预期文档未召回', count: results.value.filter((item) => !item.errorMessage && recallNeedsReview(item)).length, tone: 'amber' },
    { label: '引用缺失', count: retrievalOnly.value ? 0 : results.value.filter((item) => !item.errorMessage && citationMissing(item)).length, tone: 'ink' },
  ]
  return values.filter((item) => item.count > 0)
})
const filteredResults = computed(() => results.value.filter((result) => {
  if (sampleFilter.value === 'FAILED') return Boolean(result.errorMessage)
  if (sampleFilter.value === 'REVIEW') return !result.errorMessage && qualityLabel(result) === '需复核'
  return true
}))

const cancelMutation = useMutation({
  mutationFn: () => api.delete<void>(`/api/v1/evaluation/runs/${runId.value}`),
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ['evaluation-run', runId.value] }),
  onError: (error) => { actionError.value = readableError(error) },
})

const resumeMutation = useMutation({
  mutationFn: () => api.post<EvaluationRun>(`/api/v1/evaluation/runs/${runId.value}/resume`),
  onSuccess: (next) => {
    void queryClient.invalidateQueries({ queryKey: ['evaluation-runs'] })
    void router.replace(`/evaluation/runs/${next.id}`)
  },
  onError: (error) => { actionError.value = readableError(error) },
})

function numberValue(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function metricNumber(metrics: Record<string, unknown>, key: string) {
  return numberValue(metrics[key])
}

function percent(value: number) {
  return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`
}

function optionalPercent(value: unknown) {
  return value === undefined || value === null ? '—' : percent(numberValue(value))
}

function formatLatency(value: number) {
  if (!value) return '—'
  return value >= 1_000 ? `${(value / 1_000).toFixed(value >= 10_000 ? 0 : 1)} 秒` : `${Math.round(value)} ms`
}

function selectedMode(result: EvaluationResult) {
  return String(result.metrics.selectedMode ?? mode.value)
}

function answerScore(result: EvaluationResult) {
  if (retrievalOnly.value) return null
  if (result.metrics.semanticAnswerScore !== undefined) return numberValue(result.metrics.semanticAnswerScore)
  if (result.metrics.expectedAnswerCoverage !== undefined) return numberValue(result.metrics.expectedAnswerCoverage)
  return null
}

function answerNeedsReview(result: EvaluationResult) {
  const score = answerScore(result)
  return Boolean(result.expectedAnswer) && score !== null && score < 0.7
}

function recallNeedsReview(result: EvaluationResult) {
  return result.expectedDocumentIds.length > 0 && numberValue(result.metrics.recallAt5) < 1
}

function citationMissing(result: EvaluationResult) {
  if (retrievalOnly.value) return false
  return Boolean(result.expectedDocumentIds.length || result.expectedAnswer) && numberValue(result.metrics.citationCount) === 0
}

function qualityLabel(result: EvaluationResult) {
  if (result.errorMessage) return '未执行'
  if ((!retrievalOnly.value && answerNeedsReview(result)) || recallNeedsReview(result) || citationMissing(result)) return '需复核'
  if (!result.expectedAnswer && !result.expectedDocumentIds.length && result.caseMetadata.expectNoAnswer === undefined) return '未标注'
  return '达标'
}

function qualityClass(result: EvaluationResult) {
  const label = qualityLabel(result)
  if (label === '达标') return 'bg-evidence-50 text-evidence-700'
  if (label === '需复核') return 'bg-amber-50 text-amber-700'
  return 'bg-paper-100 text-ink-500'
}

function sampleScore(result: EvaluationResult) {
  const score = answerScore(result)
  return score === null ? '—' : percent(score)
}

function sampleMetric(result: EvaluationResult, key: string) {
  const value = result.metrics[key]
  return value === undefined || value === null ? '—' : percent(numberValue(value))
}

function scopeCount(key: string) {
  const value = snapshot.value[key]
  return Array.isArray(value) ? value.length : 0
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1280px] px-10 py-8">
    <RouterLink to="/evaluation" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950"><ArrowLeft :size="16" aria-hidden="true" />评测</RouterLink>

    <div v-if="runQuery.isPending.value" class="mt-8 space-y-5"><div class="h-24 animate-pulse bg-paper-100" /><div class="h-40 animate-pulse bg-paper-100" /><div class="h-72 animate-pulse bg-paper-100" /></div>
    <ErrorState v-else-if="runQuery.isError.value" class="mt-8" :message="readableError(runQuery.error.value)" @retry="runQuery.refetch()" />

    <template v-else-if="detail && run">
      <header class="mt-5 flex items-start justify-between gap-8 border-b border-paper-200 pb-7">
        <div class="min-w-0">
          <div class="flex items-center gap-3"><StatusPill :status="run.status" /><span class="rounded-md bg-paper-100 px-2 py-1 text-xs font-semibold text-ink-700">{{ mode }}</span></div>
          <h1 class="mt-3 truncate text-[28px] font-semibold leading-tight text-ink-950">{{ detail.dataset.name }}</h1>
          <p class="mt-2 text-sm text-ink-500">创建于 {{ formatDate(run.createdAt) }} · {{ detail.dataset.caseCount }} 个样例 · {{ retrievalOnly ? '检索与证据验收，不生成最终答案' : '执行与质量分别判定' }}</p>
        </div>
        <div class="flex items-center gap-2">
          <button type="button" class="icon-button" title="刷新" aria-label="刷新评测" @click="runQuery.refetch()"><RefreshCw :size="17" :class="runQuery.isFetching.value ? 'animate-spin' : ''" /></button>
          <button v-if="isActive" type="button" class="button-secondary text-coral-700" :disabled="cancelMutation.isPending.value" @click="cancelMutation.mutate()"><LoaderCircle v-if="cancelMutation.isPending.value" :size="16" class="animate-spin" /><Ban v-else :size="16" />停止评测</button>
          <button v-else-if="isTerminal && failedCases" type="button" class="button-primary" :disabled="resumeMutation.isPending.value" @click="resumeMutation.mutate()"><LoaderCircle v-if="resumeMutation.isPending.value" :size="16" class="animate-spin" /><RotateCcw v-else :size="16" />仅重试失败样例</button>
        </div>
      </header>

      <p v-if="actionError" class="mt-5 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">{{ actionError }}</p>

      <section v-if="isActive" class="border-b border-paper-200 py-8">
        <div class="flex items-end justify-between gap-8"><div><p class="text-sm font-semibold text-ink-950">{{ run.status === 'QUEUED' ? '等待执行资源' : '正在评测' }}</p><p class="mt-2 text-sm text-ink-500">已完成 {{ completedCases }} / {{ totalCases }} 个样例；失败不会中断其他样例。</p></div><strong class="text-3xl font-semibold tabular-nums text-ink-950">{{ progress }}%</strong></div>
        <div class="mt-5 h-2 overflow-hidden rounded-full bg-paper-200"><div class="h-full rounded-full bg-brand-600 transition-[width] duration-500" :style="{ width: `${progress}%` }" /></div>
      </section>

      <section class="py-8">
        <div class="flex items-center justify-between"><div><h2 class="text-lg font-semibold text-ink-950">结果概览</h2><p class="mt-1 text-sm text-ink-500">{{ retrievalOnly ? '本次只衡量召回、证据覆盖、成本与稳定性。' : '链路跑通是通过条件，质量分只用于衡量效果。' }}</p></div><span v-if="issues.length" class="text-sm font-medium text-amber-700">{{ issues.reduce((sum, item) => sum + item.count, 0) }} 项需关注</span></div>
        <div class="mt-5 grid grid-cols-4 divide-x divide-y divide-paper-200 border-y border-paper-200 bg-white">
          <div v-for="metric in metricCards" :key="metric.label" class="min-w-0 px-5 py-5 first:pl-0 last:pr-0">
            <p class="text-xs font-medium text-ink-500">{{ metric.label }}</p><p class="mt-2 text-2xl font-semibold tabular-nums" :class="metric.tone === 'evidence' ? 'text-evidence-700' : metric.tone === 'coral' ? 'text-coral-700' : metric.tone === 'brand' ? 'text-brand-700' : 'text-ink-950'">{{ metric.value }}</p><p class="mt-2 truncate text-xs text-ink-400" :title="metric.detail">{{ metric.detail }}</p>
          </div>
        </div>
      </section>

      <section v-if="issues.length" class="border-t border-paper-200 py-7">
        <h2 class="text-sm font-semibold text-ink-950">需要关注</h2>
        <div class="mt-4 flex flex-wrap gap-3"><span v-for="item in issues" :key="item.label" class="inline-flex h-9 items-center gap-2 rounded-md px-3 text-sm font-medium" :class="item.tone === 'coral' ? 'bg-coral-50 text-coral-700' : item.tone === 'amber' ? 'bg-amber-50 text-amber-700' : 'bg-paper-100 text-ink-600'"><CircleAlert :size="15" />{{ item.label }} <strong class="tabular-nums">{{ item.count }}</strong></span></div>
      </section>

      <section class="border-t border-paper-200 py-7">
        <div class="flex items-end justify-between gap-6"><div><h2 class="text-lg font-semibold text-ink-950">样例诊断</h2><p class="mt-1 text-sm text-ink-500">先定位执行失败和质量缺口，再进入单条证据与答案。</p></div><div class="inline-flex rounded-lg bg-paper-100 p-0.5"><button v-for="option in ([['ALL', `全部 ${results.length}`], ['REVIEW', `需复核 ${results.filter((item) => !item.errorMessage && qualityLabel(item) === '需复核').length}`], ['FAILED', `执行失败 ${results.filter((item) => item.errorMessage).length}`]] as const)" :key="option[0]" type="button" class="h-8 rounded-md px-3 text-xs font-semibold" :class="sampleFilter === option[0] ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500 hover:text-ink-900'" @click="sampleFilter = option[0]">{{ option[1] }}</button></div></div>
        <div v-if="!results.length" class="mt-5 flex min-h-36 items-center justify-center border-y border-paper-200 text-sm text-ink-500"><LoaderCircle v-if="isActive" :size="17" class="mr-2 animate-spin" />{{ isActive ? '第一批结果正在生成' : '当前评测没有结果' }}</div>
        <div v-else-if="!filteredResults.length" class="mt-5 flex min-h-28 items-center justify-center border-y border-paper-200 text-sm text-ink-500">当前筛选下没有样例</div>
        <div v-else class="mt-5">
          <div class="grid grid-cols-[48px_minmax(280px,1fr)_72px_82px_82px_90px_96px_92px_28px] gap-4 border-b border-paper-200 px-3 py-3 text-xs font-medium text-ink-400"><span>#</span><span>问题</span><span>模式</span><span>Recall@5</span><span>AEC</span><span>答案语义</span><span>质量</span><span>延迟</span><span /></div>
          <RouterLink v-for="(result, index) in filteredResults" :key="result.id" :to="`/evaluation/runs/${runId}/cases/${result.evaluationCaseId}`" class="group grid min-h-[68px] grid-cols-[48px_minmax(280px,1fr)_72px_82px_82px_90px_96px_92px_28px] items-center gap-4 border-b border-paper-200 px-3 transition-colors hover:bg-white">
            <span class="text-xs tabular-nums text-ink-400">{{ String(index + 1).padStart(2, '0') }}</span><p class="line-clamp-2 text-sm font-medium leading-5 text-ink-900 group-hover:text-brand-700">{{ result.question }}</p><span class="text-xs font-semibold text-ink-600">{{ selectedMode(result) }}</span><span class="text-xs font-semibold tabular-nums text-ink-700">{{ sampleMetric(result, 'recallAt5') }}</span><span class="text-xs font-semibold tabular-nums text-ink-700">{{ selectedMode(result) === 'FAST' ? '—' : sampleMetric(result, 'acceptedEvidenceCoverage') }}</span><span class="text-xs font-semibold tabular-nums text-ink-700">{{ sampleScore(result) }}</span><span class="inline-flex w-fit items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium" :class="qualityClass(result)"><CircleAlert v-if="result.errorMessage" :size="13" />{{ result.errorMessage ? '执行失败' : qualityLabel(result) }}</span><span class="text-xs tabular-nums text-ink-500">{{ formatLatency(numberValue(result.metrics.latencyMs)) }}</span><ArrowRight :size="16" class="text-ink-400 group-hover:text-brand-700" />
          </RouterLink>
        </div>
      </section>

      <details class="group border-t border-paper-200 py-7">
        <summary class="flex list-none items-center justify-between text-sm font-semibold text-ink-800"><span>运行配置与复现信息</span><span class="text-xs font-normal text-ink-400">{{ scopeCount('knowledgeBaseIds') }} 个知识库 · {{ scopeCount('filters') }} 个 Metadata 条件</span></summary>
        <div class="mt-5 grid grid-cols-[180px_1fr] gap-x-8 gap-y-4 text-sm"><span class="text-ink-500">执行模式</span><span class="font-medium text-ink-900">{{ mode }}</span><span class="text-ink-500">知识范围</span><span class="text-ink-800">{{ scopeCount('knowledgeBaseIds') }} 个知识库，{{ scopeCount('documentIds') }} 个指定文档</span><span class="text-ink-500">质量判定</span><span class="text-ink-800">{{ String(snapshot.judgeMode ?? aggregate.judgeMode ?? 'NONE') }}</span><span class="text-ink-500">完整快照</span><pre class="max-h-72 overflow-auto rounded-md bg-ink-950 p-4 text-xs leading-5 text-paper-100">{{ JSON.stringify(snapshot, null, 2) }}</pre></div>
      </details>
    </template>
  </div>
</template>
