import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  AnswerMode,
  Citation,
  RetrievalHealth,
  RunMode,
  RunTrace,
  RunTraceState,
  StreamEvent,
} from '@/types/api'

export type RunStatus =
  | 'accepted'
  | 'running'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'disconnected'

export interface RunRecord {
  runId: string
  conversationId?: string
  query?: string
  requestedMode?: RunMode
  selectedMode?: RunMode | null
  status: RunStatus
  answer: string
  citations: Citation[]
  answerMode?: AnswerMode | null
  retrievalHealth?: RetrievalHealth | null
  evidenceCount?: number
  trace?: RunTrace
  chatSequence: number
  events: StreamEvent[]
  startedAt: string
  completedAt?: string
  error?: string
}

const STORAGE_KEY = 'rag-workbench-runs'

function loadRuns() {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return {} as Record<string, RunRecord>
  try {
    const parsed = JSON.parse(raw) as Record<string, RunRecord>
    return Object.fromEntries(Object.entries(parsed).map(([key, run]) => [key, {
      ...run,
      chatSequence: run.chatSequence ?? 0,
      events: run.events ?? [],
      citations: run.citations ?? [],
    }]))
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return {} as Record<string, RunRecord>
  }
}

function statusFromTrace(state: RunTraceState): RunStatus {
  if (state === 'COMPLETED') return 'completed'
  if (state === 'FAILED') return 'failed'
  if (state === 'CANCELLED') return 'cancelled'
  return 'running'
}

function terminalTrace(run: RunRecord, state: Extract<RunTraceState, 'COMPLETED' | 'FAILED' | 'CANCELLED'>, timestamp: string) {
  if (!run.trace) return
  const started = new Date(run.trace.startedAt).getTime()
  const completed = new Date(timestamp).getTime()
  run.trace = {
    ...run.trace,
    state,
    completedAt: timestamp,
    durationMs: Number.isFinite(started) && Number.isFinite(completed)
      ? Math.max(0, completed - started)
      : run.trace.durationMs,
  }
}

export const useRunStore = defineStore('runs', () => {
  const records = ref<Record<string, RunRecord>>(loadRuns())
  const all = computed(() =>
    Object.values(records.value).sort(
      (left, right) =>
        new Date(right.startedAt).getTime() - new Date(left.startedAt).getTime(),
    ),
  )

  function persist() {
    const recent = Object.fromEntries(all.value.slice(0, 20).map((run) => [run.runId, run]))
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(recent))
  }

  function ensure(runId: string, seed: Partial<RunRecord> = {}) {
    if (!records.value[runId]) {
      records.value[runId] = {
        runId,
        status: 'accepted',
        answer: '',
        citations: [],
        chatSequence: 0,
        events: [],
        startedAt: new Date().toISOString(),
        ...seed,
      }
      persist()
    }
    return records.value[runId]
  }

  function register(
    runId: string,
    seed: Pick<RunRecord, 'conversationId' | 'query' | 'requestedMode'>,
  ) {
    records.value[runId] = {
      runId,
      ...seed,
      status: 'accepted',
      answer: '',
      citations: [],
      chatSequence: 0,
      events: [],
      startedAt: new Date().toISOString(),
    }
    persist()
  }

  function addCitation(run: RunRecord, citation: Citation) {
    const duplicate = run.citations.some((item) =>
      (citation.index != null && item.index === citation.index)
      || (citation.chunkId && item.chunkId === citation.chunkId),
    )
    if (!duplicate) run.citations.push(citation)
  }

  function applyCoreEvent(run: RunRecord, event: StreamEvent) {
    if (event.payload.trace && typeof event.payload.trace === 'object') {
      run.trace = event.payload.trace as RunTrace
    }
    if (event.type === 'ROUTE_SELECTED') {
      run.status = 'running'
      run.selectedMode = event.payload.selected as RunMode
    } else if (event.type === 'ANSWER_DELTA') {
      run.status = 'running'
      run.answer += String(event.payload.text ?? '')
    } else if (event.type === 'ANSWER_REPLACED') {
      run.status = 'running'
      run.answer = String(event.payload.text ?? '')
    } else if (event.type === 'CITATION') {
      addCitation(run, event.payload as Citation)
    } else if (event.type === 'ANSWER_MODE_SELECTED') {
      run.answerMode = String(event.payload.mode) as AnswerMode
      run.retrievalHealth = event.payload.retrievalHealth
        ? String(event.payload.retrievalHealth) as RetrievalHealth
        : run.retrievalHealth
      run.evidenceCount = Number(event.payload.evidenceCount ?? run.evidenceCount ?? 0)
    } else if (event.type === 'RUN_COMPLETED') {
      run.status = 'completed'
      run.completedAt = event.timestamp
      terminalTrace(run, 'COMPLETED', event.timestamp)
    } else if (event.type === 'RUN_FAILED') {
      run.status = 'failed'
      run.completedAt = event.timestamp
      run.error = String(event.payload.message ?? '暂时无法完成本次回答，请重新处理。')
      terminalTrace(run, 'FAILED', event.timestamp)
    } else if (event.type === 'RUN_CANCELLED') {
      run.status = 'cancelled'
      run.completedAt = event.timestamp
      terminalTrace(run, 'CANCELLED', event.timestamp)
    } else if (event.type !== 'RUN_ACCEPTED') {
      run.status = 'running'
    }
  }

  function applyEvent(event: StreamEvent) {
    const run = ensure(event.runId)
    if (run.events.some((item) => item.sequence === event.sequence)) return
    run.events.push(event)
    run.events.sort((left, right) => left.sequence - right.sequence)
    applyCoreEvent(run, event)
    persist()
  }

  function applyChatEvent(event: StreamEvent) {
    const run = ensure(event.runId)
    if (event.sequence <= run.chatSequence) return
    run.chatSequence = event.sequence

    if (event.type === 'TRACE_UPDATED') {
      const trace = event.payload as unknown as RunTrace
      run.trace = trace
      run.status = statusFromTrace(trace.state)
      run.startedAt = trace.startedAt || run.startedAt
      run.requestedMode = trace.requestedMode
      run.selectedMode = trace.selectedMode
      run.answerMode = trace.answerMode
      run.retrievalHealth = trace.retrievalHealth
      run.evidenceCount = trace.evidenceCount
    } else {
      applyCoreEvent(run, event)
    }
    persist()
  }

  function markDisconnected(runId: string, message: string) {
    const run = ensure(runId)
    if (run.status === 'completed' || run.status === 'failed' || run.status === 'cancelled') return
    run.status = 'disconnected'
    run.error = message
    persist()
  }

  function markCancelled(runId: string) {
    const run = ensure(runId)
    run.status = 'cancelled'
    run.completedAt = new Date().toISOString()
    terminalTrace(run, 'CANCELLED', run.completedAt)
    persist()
  }

  function latestForConversation(conversationId?: string) {
    if (!conversationId) return undefined
    return all.value.find((run) => run.conversationId === conversationId)
  }

  function clear() {
    records.value = {}
    sessionStorage.removeItem(STORAGE_KEY)
  }

  return {
    records,
    all,
    ensure,
    register,
    applyEvent,
    applyChatEvent,
    markDisconnected,
    markCancelled,
    latestForConversation,
    clear,
  }
})
