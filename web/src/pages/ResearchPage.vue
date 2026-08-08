<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowLeft,
  BookOpenCheck,
  CheckCircle2,
  CircleDashed,
  ClipboardList,
  FileSearch,
  RefreshCw,
  Square,
  Telescope,
  TriangleAlert,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { compactId, formatDate } from '@/lib/format'
import { streamRunEvents } from '@/lib/sse'
import { useRunStore } from '@/stores/runs'
import type { AgentRunArtifacts, StreamEvent, StreamEventType } from '@/types/api'

interface PlanQuestion {
  id?: string
  question?: string
  expectedEvidence?: string[]
  priority?: number
  searchMode?: string
  completionCondition?: string
}

interface CoverageItem {
  subQuestionId?: string
  covered?: boolean
  deepReadEvidenceFamilies?: number
  gaps?: string[]
  hasConflict?: boolean
}

const route = useRoute()
const runs = useRunStore()
const runId = computed(() => String(route.params.runId))
const connecting = ref(false)
let controller: AbortController | null = null

const run = computed(() => runs.records[runId.value] ?? runs.ensure(runId.value))
const artifactsQuery = useQuery(
  computed(() => ({
    queryKey: ['run-artifacts', runId.value],
    queryFn: () => api.get<AgentRunArtifacts>(`/api/v1/runs/${runId.value}/artifacts`),
    refetchInterval: (query: { state: { data?: AgentRunArtifacts } }) =>
      ['COMPLETED', 'FAILED', 'CANCELLED'].includes(
        query.state.data?.status ?? '',
      )
        ? false
        : 2_500,
  })),
)
const lastSequence = computed(
  () => run.value.events.reduce((maximum, event) => Math.max(maximum, event.sequence), 0),
)
const isTerminal = computed(() =>
  ['completed', 'failed', 'cancelled'].includes(run.value.status),
)
const isActive = computed(() => ['accepted', 'running'].includes(run.value.status))

const plan = computed(() => {
  const event = [...run.value.events].reverse().find((item) => item.type === 'PLAN_CREATED')
  if (event?.payload.subQuestions) return event.payload.subQuestions as PlanQuestion[]
  const checkpointPlan = artifactsQuery.data.value?.checkpoint.plan as
    | { subQuestions?: PlanQuestion[] }
    | undefined
  return checkpointPlan?.subQuestions ?? []
})

const coverage = computed(() => {
  const event = [...run.value.events]
    .reverse()
    .find((item) => item.type === 'COVERAGE_UPDATED')
  if (event?.payload.items) return event.payload.items as CoverageItem[]
  const persisted = artifactsQuery.data.value?.coverage.at(-1)?.report as
    | { items?: CoverageItem[] }
    | undefined
  return persisted?.items ?? []
})

const facts = computed(() => {
  const persisted = artifactsQuery.data.value?.facts.filter((item) => item.status === 'ACCEPTED') ?? []
  if (persisted.length) return persisted
  return run.value.events.filter((item) => item.type === 'FACT_ACCEPTED').map((item) => item.payload)
})

const evidence = computed(() => {
  const persisted = artifactsQuery.data.value?.evidence.filter((item) => item.deepRead) ?? []
  if (persisted.length) return persisted
  return run.value.events.filter((item) => item.type === 'DEEP_READ_COMPLETED').map((item) => item.payload)
})

const retrievalTasks = computed(() => artifactsQuery.data.value?.retrievalTasks ?? [])

const reactSteps = computed(() => artifactsQuery.data.value?.reactSteps ?? [])
const reactToolCalls = computed(() => artifactsQuery.data.value?.toolCalls ?? [])
const reactReferences = computed(() => artifactsQuery.data.value?.knowledgeReferences ?? [])

function callsForStep(stepId: unknown) {
  return reactToolCalls.value.filter((call) => call.stepId === stepId)
}

const runtimeEntries = computed(() =>
  Object.entries(artifactsQuery.data.value?.runtimeSnapshot ?? {}).filter(
    ([key]) => ['pipelineVersion', 'promptVersion', 'chatProfileId', 'queryRewriteProfileId', 'rerankProfileId'].includes(key),
  ),
)

const budgetEntries = computed(() => {
  const budget = artifactsQuery.data.value?.checkpoint.budget
  return budget && typeof budget === 'object'
    ? Object.entries(budget as Record<string, unknown>).filter(([key]) =>
        [
          'roundsUsed',
          'maxRounds',
          'searchesUsed',
          'maxSearches',
          'deepReadsUsed',
          'maxDeepReads',
          'maxParallelism',
        ].includes(key),
      )
    : []
})

const visibleEvents = computed(() =>
  run.value.events.filter((event) => event.type !== 'ANSWER_DELTA'),
)

const eventLabels: Record<StreamEventType, string> = {
  RUN_ACCEPTED: '运行已接收',
  RUN_RECOVERED: '运行已恢复',
  ROUTE_SELECTED: '路由已选择',
  INTENT_CLASSIFIED: '意图已识别',
  TRACE_UPDATED: '聊天轨迹已更新',
  QUERY_REWRITE_STARTED: '开始改写查询',
  QUERY_REWRITTEN: '查询已改写',
  MEMORY_APPLIED: '已应用个性化记忆',
  RETRIEVAL_STARTED: '开始检索',
  RETRIEVAL_RESULT: '召回候选',
  RERANK_COMPLETED: '重排完成',
  RERANK_SKIPPED: '重排已降级',
  NO_ANSWER: '证据不足',
  PARTIAL_ANSWER: '部分回答（带证据边界）',
  CITATION_VERIFIED: '引用已校验',
  PLAN_CREATED: '研究计划已生成',
  GOAL_RESEARCH_STARTED: '研究目标开始检索',
  GOAL_RESEARCH_COMPLETED: '研究目标检索完成',
  GOAL_RESEARCH_FAILED: '研究目标检索降级',
  DEEP_READ_STARTED: '开始提取原文证据',
  RETRIEVAL_TASK_CREATED: '检索任务已创建',
  RETRIEVAL_TASK_STARTED: '检索任务执行中',
  RETRIEVAL_TASK_COMPLETED: '检索任务已完成',
  RETRIEVAL_TASK_FAILED: '检索任务失败',
  DEEP_READ_COMPLETED: '深读证据完成',
  DEEP_READ_FAILED: '深读证据抽取失败',
  FACT_ACCEPTED: '事实已采纳',
  FACT_REJECTED: '事实已拒绝',
  CONFLICT_DETECTED: '事实冲突已识别',
  EVIDENCE_JUDGE_STARTED: 'Evidence Judge 开始',
  EVIDENCE_JUDGE_COMPLETED: 'Evidence Judge 已完成',
  EVIDENCE_JUDGE_FAILED: 'Evidence Judge 调用失败',
  COVERAGE_UPDATED: '覆盖度已更新',
  GAP_IDENTIFIED: '覆盖缺口已识别',
  GAP_QUERY_CREATED: '补充查询已生成',
  BUDGET_UPDATED: '预算已更新',
  REACT_ROUND_STARTED: 'ReAct 回合开始',
  AGENT_ACTION_UPDATED: 'Agent 动作已更新',
  TOOL_CALL_STARTED: '工具调用开始',
  TOOL_CALL_COMPLETED: '工具调用完成',
  TOOL_CALL_FAILED: '工具调用失败',
  CONTEXT_COMPRESSED: '上下文已压缩',
  ANSWER_GENERATION_STARTED: '开始生成回答',
  ANSWER_DELTA: '答案生成中',
  ANSWER_MODE_SELECTED: '回答策略已选择',
  CITATION: '引用已关联',
  ANSWER_REPLACED: '答案已完成引用复验',
  RUN_COMPLETED: '运行完成',
  RUN_CANCELLED: '运行已取消',
  RUN_FAILED: '运行失败',
}

function summarizeEvent(event: StreamEvent) {
  if (event.type === 'ROUTE_SELECTED') {
    return `${String(event.payload.requested ?? 'AUTO')} → ${String(event.payload.selected ?? '—')}`
  }
  if (event.type === 'RETRIEVAL_RESULT') {
    return `${String(event.payload.candidateCount ?? 0)} 个候选`
  }
  if (event.type === 'RERANK_COMPLETED') {
    const candidates = String(event.payload.candidateCount ?? 0)
    const results = String(event.payload.resultCount ?? event.payload.candidateCount ?? 0)
    return `${candidates} 个候选 → ${results} 个结果`
  }
  if (event.type === 'RERANK_SKIPPED') {
    return String(event.payload.reason ?? '使用召回顺序降级')
  }
  if (event.type === 'QUERY_REWRITTEN') {
    return String(event.payload.rewritten ?? '')
  }
  if (event.type === 'MEMORY_APPLIED') {
    return `${String(event.payload.count ?? 0)} 条，仅用于个性化`
  }
  if (event.type === 'RETRIEVAL_TASK_COMPLETED') {
    return `${String(event.payload.resultCount ?? 0)} 个结果`
  }
  if (event.type === 'EVIDENCE_JUDGE_COMPLETED') {
    return event.payload.sufficient ? '证据充分，可生成回答' : '证据不足，继续补检'
  }
  if (event.type === 'EVIDENCE_JUDGE_STARTED') {
    return `第 ${String(event.payload.round ?? '?')} 轮 · ${String(event.payload.evidenceCount ?? 0)} 条证据`
  }
  if (event.type === 'EVIDENCE_JUDGE_FAILED') {
    return String(event.payload.reason ?? 'Evidence Judge 未能完成，已按证据不足处理')
  }
  if (event.type === 'DEEP_READ_FAILED') {
    return String(event.payload.reason ?? '无法从扩展上下文抽取有效原文证据')
  }
  if (event.type === 'REACT_ROUND_STARTED') {
    return `第 ${String(event.payload.round ?? '?')} 回合`
  }
  if (event.type === 'TOOL_CALL_STARTED' || event.type === 'TOOL_CALL_COMPLETED' || event.type === 'TOOL_CALL_FAILED') {
    const count = event.payload.resultCount == null ? '' : ` · ${String(event.payload.resultCount)} 个结果`
    return `${String(event.payload.tool ?? 'knowledge tool')}${count}`
  }
  if (event.type === 'GAP_QUERY_CREATED') {
    return String(event.payload.query ?? '')
  }
  if (event.type === 'FACT_REJECTED' || event.type === 'CONFLICT_DETECTED') {
    return String(event.payload.reason ?? '')
  }
  if (event.type === 'RUN_FAILED') {
    return String(event.payload.message ?? '运行失败')
  }
  return ''
}

function connect() {
  controller?.abort()
  const nextController = new AbortController()
  controller = nextController
  connecting.value = true

  void streamRunEvents(runId.value, {
    after: lastSequence.value,
    signal: nextController.signal,
    onEvent: (event) => runs.applyEvent(event),
  })
    .catch((error) => {
      if (!nextController.signal.aborted) {
        runs.markDisconnected(runId.value, readableError(error))
      }
    })
    .finally(() => {
      if (controller === nextController) connecting.value = false
    })
}

async function cancelRun() {
  try {
    await api.delete<void>(`/api/v1/runs/${runId.value}`)
    runs.markCancelled(runId.value)
    controller?.abort()
  } catch (error) {
    runs.markDisconnected(runId.value, readableError(error))
  }
}

onMounted(() => {
  if (!isTerminal.value) connect()
})

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="mx-auto w-full max-w-7xl px-4 py-7 sm:px-7 sm:py-10 lg:px-10">
    <RouterLink
      :to="run.conversationId ? `/chat?conversation=${run.conversationId}` : '/chat'"
      class="inline-flex items-center gap-2 text-sm text-ink-600 hover:text-brand-700"
    >
      <ArrowLeft :size="17" aria-hidden="true" />
      返回对话
    </RouterLink>

    <header
      class="mt-6 flex flex-col gap-5 border-b border-paper-200 pb-7 sm:flex-row sm:items-end sm:justify-between"
    >
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-3">
          <p class="section-label">Research run</p>
          <StatusPill :status="run.status" />
        </div>
        <h1 class="page-title mt-2">研究运行</h1>
        <p class="mt-2 max-w-3xl break-words text-sm leading-6 text-ink-600">
          {{ run.query || artifactsQuery.data.value?.query || `运行 ${compactId(runId)}` }}
        </p>
      </div>
      <div class="flex gap-2 self-start sm:self-auto">
        <button
          v-if="run.status === 'disconnected'"
          type="button"
          class="button-secondary"
          @click="connect"
        >
          <RefreshCw :size="17" :class="{ 'animate-spin': connecting }" aria-hidden="true" />
          重新连接
        </button>
        <button v-if="isActive" type="button" class="button-secondary" @click="cancelRun">
          <Square :size="15" fill="currentColor" aria-hidden="true" />
          停止
        </button>
      </div>
    </header>

    <div class="grid gap-10 py-8 lg:grid-cols-[minmax(0,1fr)_320px]">
      <main class="min-w-0">
        <section class="border-b border-paper-200 pb-8">
          <div class="flex items-center justify-between gap-4">
            <div class="flex items-center gap-2">
              <Telescope :size="19" class="text-brand-700" aria-hidden="true" />
              <h2 class="text-base font-semibold">研究结论</h2>
            </div>
            <span class="text-xs text-ink-400">
              {{ run.selectedMode || run.requestedMode || 'AUTO' }}
            </span>
          </div>

          <p
            v-if="run.answer"
            class="mt-5 whitespace-pre-wrap break-words text-[15px] leading-8 text-ink-800"
          >
            {{ run.answer }}
          </p>
          <div v-else class="mt-5 flex min-h-32 items-center justify-center bg-paper-100 px-5 text-center">
            <div>
              <CircleDashed
                :size="22"
                class="mx-auto text-ink-400"
                :class="{ 'animate-spin': isActive || connecting }"
                aria-hidden="true"
              />
              <p class="mt-3 text-sm text-ink-600">
                {{
                  run.error ||
                  (isActive || connecting ? '等待答案事件' : '当前运行没有可显示的答案')
                }}
              </p>
            </div>
          </div>

          <div v-if="run.error" class="mt-4 flex gap-3 rounded-md bg-coral-50 px-4 py-3">
            <TriangleAlert :size="18" class="mt-0.5 shrink-0 text-coral-700" aria-hidden="true" />
            <p class="text-sm leading-6 text-coral-700">{{ run.error }}</p>
          </div>

          <div v-if="run.citations.length" class="mt-7">
            <p class="section-label mb-3">引用证据</p>
            <details
              v-for="citation in run.citations"
              :key="`${citation.index}-${citation.chunkId}`"
              class="border-t border-paper-200 py-3 last:border-b"
            >
              <summary class="cursor-pointer list-none text-sm font-medium">
                <span class="mr-2 text-brand-700">[{{ citation.index ?? '?' }}]</span>
                {{ citation.documentTitle || '未命名文档' }}
              </summary>
              <p class="mt-2 pl-7 text-sm leading-6 text-ink-600">
                {{ citation.quote || '该事件未包含引用摘录。' }}
              </p>
            </details>
          </div>
        </section>

        <section v-if="reactSteps.length" class="border-b border-paper-200 py-8">
          <div class="flex items-center gap-2">
            <Telescope :size="19" class="text-brand-700" aria-hidden="true" />
            <h2 class="text-base font-semibold">ReAct 研究轨迹</h2>
          </div>
          <ol class="mt-5 space-y-4">
            <li
              v-for="step in reactSteps"
              :key="String(step.id)"
              class="rounded-md border border-paper-200 bg-paper-50 px-4 py-4"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="text-sm font-semibold">回合 {{ String(step.stepNumber) }} · {{ String(step.actionSummary || 'Agent 动作') }}</p>
                <span class="text-xs text-ink-400">{{ String(step.finishReason || step.status || '') }}</span>
              </div>
              <p v-if="step.assistantContent" class="mt-2 text-sm leading-6 text-ink-600">
                {{ String(step.assistantContent) }}
              </p>
              <div v-if="callsForStep(step.id).length" class="mt-3 divide-y divide-paper-200 border-y border-paper-200">
                <div v-for="call in callsForStep(step.id)" :key="String(call.id)" class="py-3 text-xs">
                  <div class="flex items-center justify-between gap-3">
                    <span class="font-medium text-ink-700">{{ String(call.toolName) }}</span>
                    <span :class="call.status === 'FAILED' ? 'text-coral-700' : 'text-brand-700'">
                      {{ String(call.status) }} · {{ String(call.resultCount ?? 0) }} 条 · {{ String(call.latencyMs ?? 0) }} ms
                    </span>
                  </div>
                  <p class="mt-1 break-all text-ink-400">{{ JSON.stringify(call.arguments || {}) }}</p>
                </div>
              </div>
            </li>
          </ol>
          <p class="mt-4 text-xs text-ink-400">
            已投影 {{ reactReferences.length }} 条知识引用；隐藏推理内容不会展示。
          </p>
        </section>

        <section v-if="plan.length" class="border-b border-paper-200 py-8">
          <div class="flex items-center gap-2">
            <ClipboardList :size="19" class="text-amber-700" aria-hidden="true" />
            <h2 class="text-base font-semibold">研究计划</h2>
          </div>
          <ol class="mt-5 divide-y divide-paper-200 border-y border-paper-200">
            <li v-for="(question, index) in plan" :key="question.id || index" class="flex gap-4 py-4">
              <span class="text-sm font-semibold text-ink-400">{{ index + 1 }}</span>
              <div>
                <p class="text-sm font-medium leading-6">{{ question.question || '未命名子问题' }}</p>
                <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-400">
                  <span v-if="question.searchMode">{{ question.searchMode }}</span>
                  <span v-if="question.completionCondition">完成条件：{{ question.completionCondition }}</span>
                </div>
                <p v-if="question.expectedEvidence?.length" class="mt-1 text-xs leading-5 text-ink-400">
                  {{ question.expectedEvidence.join('；') }}
                </p>
              </div>
            </li>
          </ol>
        </section>

        <section v-if="retrievalTasks.length" class="border-b border-paper-200 py-8">
          <div class="flex items-center gap-2">
            <FileSearch :size="19" class="text-brand-700" aria-hidden="true" />
            <h2 class="text-base font-semibold">分 Query 检索</h2>
          </div>
          <div class="mt-5 divide-y divide-paper-200 border-y border-paper-200">
            <article v-for="task in retrievalTasks" :key="String(task.id)" class="py-4">
              <div class="flex flex-wrap items-center justify-between gap-2 text-xs">
                <span class="font-medium text-brand-700">
                  第 {{ String(task.round ?? '?') }} 轮 · {{ String(task.searchMode ?? 'HYBRID') }}
                </span>
                <span :class="task.status === 'FAILED' ? 'text-coral-700' : 'text-ink-400'">
                  {{ String(task.status ?? '') }} · {{ String(task.resultCount ?? 0) }} 条
                </span>
              </div>
              <p class="mt-2 break-words text-sm leading-6 text-ink-700">{{ String(task.query ?? '') }}</p>
              <p v-if="task.errorMessage" class="mt-1 text-xs leading-5 text-coral-700">
                {{ String(task.errorMessage) }}
              </p>
            </article>
          </div>
        </section>

        <section v-if="facts.length || evidence.length" class="py-8">
          <div class="flex items-center gap-2">
            <BookOpenCheck :size="19" class="text-brand-700" aria-hidden="true" />
            <h2 class="text-base font-semibold">证据与事实</h2>
          </div>
          <div class="mt-5 divide-y divide-paper-200 border-y border-paper-200">
            <article v-for="(fact, index) in facts" :key="String(fact.id || index)" class="py-4">
              <div class="flex items-start gap-3">
                <CheckCircle2 :size="17" class="mt-1 shrink-0 text-brand-700" aria-hidden="true" />
                <div>
                  <p class="text-sm leading-7 text-ink-800">
                    {{ String(fact.statement || '已采纳事实') }}
                  </p>
                  <p class="mt-1 text-xs text-ink-400">
                    置信度 {{ Math.round(Number(fact.confidence || 0) * 100) }}%
                  </p>
                </div>
              </div>
            </article>
            <article
              v-for="(item, index) in evidence"
              :key="String(item.id || index)"
              class="py-4"
            >
              <div class="flex items-start gap-3">
                <FileSearch :size="17" class="mt-1 shrink-0 text-amber-700" aria-hidden="true" />
                <div class="min-w-0">
                  <p class="break-words text-sm leading-7 text-ink-600">
                    {{ String(item.quote || '深读证据事件') }}
                  </p>
                  <p v-if="item.sourceStart != null" class="mt-1 break-words text-xs text-ink-400">
                    原文位置 {{ String(item.sourceStart) }}–{{ String(item.sourceEnd) }} ·
                    {{ Array.isArray(item.retrievalSources) ? item.retrievalSources.join(' / ') : 'deep-read' }}
                  </p>
                </div>
              </div>
            </article>
          </div>
        </section>
      </main>

      <aside class="min-w-0 lg:border-l lg:border-paper-200 lg:pl-7">
        <section v-if="coverage.length" class="border-b border-paper-200 pb-7">
          <p class="section-label">覆盖度</p>
          <div class="mt-4 space-y-4">
            <div v-for="(item, index) in coverage" :key="item.subQuestionId || index">
              <div class="flex items-center justify-between gap-3 text-sm">
                <span>子问题 {{ index + 1 }}</span>
                <span :class="item.covered ? 'text-brand-700' : 'text-amber-700'">
                  {{ item.covered ? '已覆盖' : '有缺口' }}
                </span>
              </div>
              <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-paper-200">
                <div
                  class="h-full rounded-full"
                  :class="item.covered ? 'w-full bg-brand-600' : 'w-1/3 bg-amber-700'"
                />
              </div>
              <p v-if="item.gaps?.length" class="mt-2 text-xs leading-5 text-ink-400">
                {{ item.gaps.join('；') }}
              </p>
            </div>
          </div>
        </section>

        <section class="pt-7">
          <div v-if="runtimeEntries.length" class="mb-7 border-b border-paper-200 pb-7">
            <p class="section-label">运行配置</p>
            <dl class="mt-4 space-y-3">
              <div v-for="entry in runtimeEntries" :key="entry[0]" class="grid grid-cols-[110px_1fr] gap-3 text-xs">
                <dt class="break-all text-ink-400">{{ entry[0] }}</dt>
                <dd class="break-all text-ink-700">{{ String(entry[1]) }}</dd>
              </div>
            </dl>
          </div>
          <div v-if="budgetEntries.length" class="mb-7 border-b border-paper-200 pb-7">
            <p class="section-label">Agent 预算</p>
            <dl class="mt-4 space-y-3">
              <div v-for="entry in budgetEntries" :key="entry[0]" class="grid grid-cols-[110px_1fr] gap-3 text-xs">
                <dt class="break-all text-ink-400">{{ entry[0] }}</dt>
                <dd class="break-all text-ink-700">{{ String(entry[1]) }}</dd>
              </div>
            </dl>
          </div>
          <div class="flex items-center justify-between">
            <p class="section-label">事件轨迹</p>
            <span class="text-xs text-ink-400">{{ visibleEvents.length }}</span>
          </div>
          <div v-if="!visibleEvents.length" class="py-8 text-sm leading-6 text-ink-400">
            {{ connecting ? '正在连接事件流…' : '暂无运行事件' }}
          </div>
          <ol v-else class="mt-4">
            <li
              v-for="event in visibleEvents"
              :key="event.eventId"
              class="relative border-l border-paper-200 pb-5 pl-5 last:pb-0"
            >
              <span
                class="absolute -left-1 top-1 size-2 rounded-full bg-ink-400 ring-4 ring-paper-50"
              />
              <p class="text-sm font-medium">{{ eventLabels[event.type] }}</p>
              <p v-if="summarizeEvent(event)" class="mt-1 break-words text-xs leading-5 text-ink-600">
                {{ summarizeEvent(event) }}
              </p>
              <p class="mt-1 text-[11px] text-ink-400">{{ formatDate(event.timestamp) }}</p>
            </li>
          </ol>
        </section>
      </aside>
    </div>
  </div>
</template>
