<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowLeft,
  ArrowUpRight,
  CheckCircle2,
  CircleAlert,
  Clock3,
  Network,
  Route,
  Zap,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import ErrorState from '@/components/ErrorState.vue'
import { api, readableError } from '@/lib/api'
import { compactId } from '@/lib/format'
import type { EvaluationResult, EvaluationRunDetail } from '@/types/api'

interface RankedDocument {
  rank?: number
  documentId?: string
  title?: string
  score?: number
}

const route = useRoute()
const runId = computed(() => String(route.params.runId))
const caseId = computed(() => String(route.params.caseId))

const runQuery = useQuery(
  computed(() => ({
    queryKey: ['evaluation-run', runId.value],
    queryFn: () => api.get<EvaluationRunDetail>(`/api/v1/evaluation/runs/${runId.value}`),
  })),
)

const result = computed(() => runQuery.data.value?.results.find((item) => item.evaluationCaseId === caseId.value))
const metrics = computed(() => result.value?.metrics ?? {})
const selectedMode = computed(() => String(metrics.value.selectedMode ?? runQuery.data.value?.requestSnapshot.mode ?? runQuery.data.value?.run.aggregateMetrics.requestedMode ?? 'AUTO'))
const modeIcon = computed(() => selectedMode.value === 'FAST' ? Zap : selectedMode.value === 'DEEP' ? Network : Route)
const topDocuments = computed<RankedDocument[]>(() => Array.isArray(metrics.value.topDocuments) ? metrics.value.topDocuments as RankedDocument[] : [])
const expectedDocuments = computed(() => new Set(result.value?.expectedDocumentIds ?? []))
const answer = computed(() => String(metrics.value.answer ?? ''))
const expectsNoAnswer = computed(() => result.value?.caseMetadata.expectNoAnswer)
const retrievalOnly = computed(() => String(runQuery.data.value?.requestSnapshot.execution ?? runQuery.data.value?.run.aggregateMetrics.execution) === 'AGENTIC_RETRIEVAL_ONLY')

const scoreFacts = computed(() => [
  { label: '答案语义', value: retrievalOnly.value ? '—' : optionalPercent(metrics.value.semanticAnswerScore ?? metrics.value.expectedAnswerCoverage) },
  { label: 'Recall@5', value: result.value?.expectedDocumentIds.length ? optionalPercent(metrics.value.recallAt5) : '未标注' },
  { label: 'AEC', value: selectedMode.value === 'FAST' ? '—' : optionalPercent(metrics.value.acceptedEvidenceCoverage) },
  { label: 'RCC', value: optionalPercent(metrics.value.researchContextCoverage) },
  { label: '引用支持', value: retrievalOnly.value ? '—' : numberValue(metrics.value.citationCount) ? optionalPercent(metrics.value.citationEntailmentScore ?? metrics.value.citationResolvableRate) : '无引用' },
  { label: '延迟', value: formatLatency(numberValue(metrics.value.latencyMs)) },
])

function numberValue(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function optionalPercent(value: unknown) {
  if (value === undefined || value === null) return '未判定'
  return `${Math.round(Math.max(0, Math.min(1, numberValue(value))) * 100)}%`
}

function formatLatency(value: number) {
  if (!value) return '—'
  return value >= 1_000 ? `${(value / 1_000).toFixed(value >= 10_000 ? 0 : 1)} 秒` : `${Math.round(value)} ms`
}

function answerNeedsReview(item: EvaluationResult) {
  if (retrievalOnly.value) return false
  const score = item.metrics.semanticAnswerScore ?? item.metrics.expectedAnswerCoverage
  return Boolean(item.expectedAnswer) && score !== undefined && numberValue(score) < 0.7
}

function recallNeedsReview(item: EvaluationResult) {
  return item.expectedDocumentIds.length > 0 && numberValue(item.metrics.recallAt5) < 1
}

const quality = computed(() => {
  const item = result.value
  if (!item) return { label: '未知', className: 'bg-paper-100 text-ink-500' }
  if (item.errorMessage) return { label: '未执行', className: 'bg-paper-100 text-ink-500' }
  if (answerNeedsReview(item) || recallNeedsReview(item)) return { label: '需复核', className: 'bg-amber-50 text-amber-700' }
  if (!item.expectedAnswer && !item.expectedDocumentIds.length && item.caseMetadata.expectNoAnswer === undefined) return { label: '未标注', className: 'bg-paper-100 text-ink-500' }
  return { label: '达标', className: 'bg-evidence-50 text-evidence-700' }
})

function isExpected(documentId?: string) {
  return Boolean(documentId && expectedDocuments.value.has(documentId))
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1120px] px-10 py-8">
    <RouterLink :to="`/evaluation/runs/${runId}`" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950"><ArrowLeft :size="16" aria-hidden="true" />返回评测结果</RouterLink>

    <div v-if="runQuery.isPending.value" class="mt-8 space-y-5"><div class="h-28 animate-pulse bg-paper-100" /><div class="h-72 animate-pulse bg-paper-100" /></div>
    <ErrorState v-else-if="runQuery.isError.value" class="mt-8" :message="readableError(runQuery.error.value)" @retry="runQuery.refetch()" />
    <div v-else-if="!result" class="mt-8 border-y border-paper-200 py-16 text-center"><p class="text-base font-semibold text-ink-950">找不到这个评测样例</p><p class="mt-2 text-sm text-ink-500">它可能尚未执行，或已从评测数据集中移除。</p></div>

    <template v-else>
      <header class="mt-5 border-b border-paper-200 pb-7">
        <div class="flex items-center gap-2">
          <span class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium" :class="result.errorMessage ? 'bg-coral-50 text-coral-700' : 'bg-evidence-50 text-evidence-700'"><CircleAlert v-if="result.errorMessage" :size="13" /><CheckCircle2 v-else :size="13" />{{ result.errorMessage ? '链路执行失败' : retrievalOnly ? '研究链路成功' : '链路执行成功' }}</span>
          <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium" :class="quality.className">质量{{ quality.label }}</span>
        </div>
        <h1 class="mt-4 max-w-[900px] text-[24px] font-semibold leading-9 text-ink-950">{{ result.question }}</h1>
        <div class="mt-4 flex items-center gap-5 text-xs text-ink-500"><span class="inline-flex items-center gap-1.5"><component :is="modeIcon" :size="14" />{{ selectedMode }} 模式</span><span class="inline-flex items-center gap-1.5"><Clock3 :size="14" />{{ formatLatency(numberValue(metrics.latencyMs)) }}</span><span>{{ compactId(result.id) }}</span></div>
      </header>

      <section v-if="result.errorMessage" class="border-b border-paper-200 py-7">
        <div class="flex items-start gap-3 rounded-md bg-coral-50 px-4 py-4 text-coral-700"><CircleAlert :size="18" class="mt-0.5 shrink-0" /><div><h2 class="text-sm font-semibold">执行未得到最终答案</h2><p class="mt-1 text-sm leading-6">{{ result.errorMessage }}</p><p class="mt-2 text-xs opacity-80">此项只计入链路失败，不会被误判为答案质量差。</p></div></div>
      </section>

      <section class="grid grid-cols-6 divide-x divide-paper-200 border-b border-paper-200 bg-white">
        <div v-for="fact in scoreFacts" :key="fact.label" class="px-5 py-5 first:pl-0"><p class="text-xs font-medium text-ink-500">{{ fact.label }}</p><p class="mt-2 text-xl font-semibold tabular-nums text-ink-950">{{ fact.value }}</p></div>
      </section>

      <section class="border-b border-paper-200 py-8">
        <div class="grid grid-cols-[150px_1fr] gap-x-8 gap-y-8">
          <div><h2 class="text-sm font-semibold text-ink-950">最终答案</h2><p class="mt-1 text-xs leading-5 text-ink-500">链路实际输出</p></div>
          <div class="min-w-0 whitespace-pre-wrap text-[15px] leading-7 text-ink-800">{{ retrievalOnly ? '本次评测止于检索与证据验收，不生成最终答案。' : answer || (result.errorMessage ? '未生成' : '该链路返回了空答案') }}</div>

          <div><h2 class="text-sm font-semibold text-ink-950">标准答案</h2><p class="mt-1 text-xs leading-5 text-ink-500">仅用于质量衡量</p></div>
          <div class="min-w-0 text-[15px] leading-7 text-ink-800"><p v-if="result.expectedAnswer" class="whitespace-pre-wrap">{{ result.expectedAnswer }}</p><p v-else-if="expectsNoAnswer === true" class="text-ink-500">预期拒答或明确说明证据不足</p><p v-else-if="expectsNoAnswer === false" class="text-ink-500">预期能够作答，未提供标准答案文本</p><p v-else class="text-ink-400">未提供标准答案</p></div>
        </div>
      </section>

      <section class="border-b border-paper-200 py-8">
        <div class="flex items-end justify-between"><div><h2 class="text-lg font-semibold text-ink-950">检索文档</h2><p class="mt-1 text-sm text-ink-500">前五个文档及其与预期文档的对应关系。</p></div><span class="text-xs text-ink-400">预期 {{ result.expectedDocumentIds.length }} 个</span></div>
        <div v-if="topDocuments.length" class="mt-5 border-t border-paper-200">
          <div v-for="document in topDocuments" :key="`${document.rank}-${document.documentId}`" class="grid min-h-14 grid-cols-[48px_minmax(260px,1fr)_160px_100px] items-center gap-4 border-b border-paper-200 px-3"><span class="text-xs tabular-nums text-ink-400">#{{ document.rank }}</span><span class="truncate text-sm font-medium text-ink-900">{{ document.title || compactId(document.documentId) }}</span><span class="text-xs text-ink-400">{{ compactId(document.documentId) }}</span><span class="text-right"><span v-if="isExpected(document.documentId)" class="rounded-full bg-evidence-50 px-2.5 py-1 text-xs font-medium text-evidence-700">预期文档</span><span v-else class="text-xs tabular-nums text-ink-500">{{ numberValue(document.score).toFixed(3) }}</span></span></div>
        </div>
        <p v-else class="mt-5 border-y border-paper-200 py-8 text-center text-sm text-ink-500">没有保存可展示的召回文档。</p>
        <div v-if="result.expectedDocumentIds.length" class="mt-5"><p class="text-xs font-medium text-ink-500">预期文档 ID</p><div class="mt-2 flex flex-wrap gap-2"><span v-for="documentId in result.expectedDocumentIds" :key="documentId" class="rounded-md bg-paper-100 px-2.5 py-1.5 font-mono text-xs text-ink-600">{{ compactId(documentId) }}</span></div></div>
      </section>

      <section v-if="result.ragRunId" class="flex items-center justify-between border-b border-paper-200 py-7"><div><h2 class="text-sm font-semibold text-ink-950">链路运行记录</h2><p class="mt-1 text-xs text-ink-500">查看检索、路由、深读和引用校验过程。</p></div><RouterLink :to="`/research/${result.ragRunId}`" class="button-secondary">查看运行详情<ArrowUpRight :size="16" /></RouterLink></section>

      <details class="group py-7">
        <summary class="flex list-none items-center justify-between text-sm font-semibold text-ink-800"><span>全部技术指标</span><span class="text-xs font-normal text-ink-400">故障排查与实验复现</span></summary>
        <pre class="mt-5 max-h-[480px] overflow-auto rounded-md bg-ink-950 p-4 text-xs leading-5 text-paper-100">{{ JSON.stringify(metrics, null, 2) }}</pre>
      </details>
    </template>
  </div>
</template>
