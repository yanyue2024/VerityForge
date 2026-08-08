<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowDown,
  ArrowUp,
  BookOpenCheck,
  BookOpenText,
  Check,
  ChevronDown,
  LoaderCircle,
  Network,
  Route,
  Sparkles,
  Square,
  Zap,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import ChatMessage from '@/components/ChatMessage.vue'
import CitationPanel from '@/components/CitationPanel.vue'
import ErrorState from '@/components/ErrorState.vue'
import MetadataFilterBuilder from '@/components/MetadataFilterBuilder.vue'
import QuestionSuggestions from '@/components/QuestionSuggestions.vue'
import { api, readableError } from '@/lib/api'
import { filterReferencedCitations } from '@/lib/citations'
import { streamRunEvents } from '@/lib/sse'
import { useRunStore, type RunRecord, type RunStatus } from '@/stores/runs'
import type {
  Citation,
  Conversation,
  ConversationMessage,
  ConversationSettings,
  CreateRunRequest,
  DocumentDetail,
  KnowledgeBase,
  QuestionSuggestionRequest,
  QuestionSuggestionResponse,
  RunAccepted,
  RunMode,
  RunTrace,
} from '@/types/api'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const runs = useRunStore()

const selectedConversationId = ref(typeof route.query.conversation === 'string' ? route.query.conversation : '')
const queryText = ref('')
const mode = ref<RunMode>('AUTO')
const selectedKnowledgeBaseIds = ref<string[]>([])
const metadataFilters = ref<CreateRunRequest['filters']>([])
const scopeOpen = ref(false)
const submitError = ref('')
const composer = ref<HTMLTextAreaElement | null>(null)
const messagesViewport = ref<HTMLElement | null>(null)
const messagesContent = ref<HTMLElement | null>(null)
const citationPanelOpen = ref(false)
const activeCitations = ref<Citation[]>([])
const activeEvidenceIndex = ref<number | null>(null)
const activeCitationMode = ref<Exclude<RunMode, 'AUTO'> | null>(null)
const historicalTraces = ref<Record<string, RunTrace>>({})
const traceLoading = ref<Record<string, boolean>>({})
const reprocessingRunId = ref('')
const userDetachedFromLatest = ref(false)
const showReturnToLatest = ref(false)
const suggestionResponse = ref<QuestionSuggestionResponse | null>(null)
const suggestionState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const suggestionClientKey = ref('')
const suggestionRefreshing = ref(false)
let streamController: AbortController | null = null
let suggestionController: AbortController | null = null
let suggestionTimer: number | undefined
let suggestionSequence = 0
let settingsTimer: number | undefined
let applyingSettings = false
let contentResizeObserver: ResizeObserver | null = null

const conversationQuery = useQuery(
  computed(() => ({
    queryKey: ['conversation', selectedConversationId.value],
    queryFn: () => api.get<Conversation>(`/api/v1/conversations/${selectedConversationId.value}`),
    enabled: Boolean(selectedConversationId.value),
  })),
)

const knowledgeQuery = useQuery({
  queryKey: ['knowledge-bases'],
  queryFn: () => api.get<KnowledgeBase[]>('/api/v1/knowledge-bases'),
})

const messagesQuery = useQuery(
  computed(() => ({
    queryKey: ['conversation-messages', selectedConversationId.value],
    queryFn: () => api.get<ConversationMessage[]>(`/api/v1/conversations/${selectedConversationId.value}/messages`),
    enabled: Boolean(selectedConversationId.value),
  })),
)

const latestRun = computed(() => runs.latestForConversation(selectedConversationId.value))
const isRunning = computed(() => latestRun.value?.status === 'accepted' || latestRun.value?.status === 'running')

const showLatestRun = computed(() => {
  const run = latestRun.value
  if (!run) return false
  const echoedByServer = (messagesQuery.data.value ?? []).some(
    (message) => message.role === 'assistant' && message.runId === run.runId,
  )
  return !echoedByServer
})

const latestAnswerEvidence = computed(() => {
  const run = latestRun.value
  if (showLatestRun.value && run) {
    return {
      citations: filterReferencedCitations(run.answer ?? '', run.citations ?? []),
      mode: run.selectedMode === 'FAST' || run.selectedMode === 'DEEP' ? run.selectedMode : null,
    }
  }

  const messages = messagesQuery.data.value ?? []
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message.role !== 'assistant') continue
    return {
      citations: filterReferencedCitations(message.content, message.citations ?? []),
      mode: message.selectedMode,
    }
  }

  return { citations: [], mode: null }
})

const liveUserMessage = computed(() => {
  const run = latestRun.value
  if (!run || !showLatestRun.value) return undefined
  return (messagesQuery.data.value ?? []).find(
    (message) => message.role === 'user' && message.runId === run.runId,
  )
})

const showWelcome = computed(() => {
  if (showLatestRun.value) return false
  if (!selectedConversationId.value) return true
  if (messagesQuery.isPending.value || messagesQuery.isError.value) return false
  return !messagesQuery.data.value?.length
})

// Draft text must not reflow the welcome screen. Suggestions stay in place until
// the user actually starts a conversation by sending a message.
const suggestionsVisible = computed(() => showWelcome.value)

const currentSuggestionClientKey = computed(() => JSON.stringify({
  mode: mode.value,
  knowledgeBaseIds: [...selectedKnowledgeBaseIds.value].sort(),
  filters: metadataFilters.value,
}))

const scopeLabel = computed(() => {
  if (!selectedKnowledgeBaseIds.value.length) return '知识范围'
  if (selectedKnowledgeBaseIds.value.length === 1) {
    return knowledgeQuery.data.value?.find((item) => item.id === selectedKnowledgeBaseIds.value[0])?.name ?? '1 个知识库'
  }
  return `${selectedKnowledgeBaseIds.value.length} 个知识库`
})

const modeOptions = [
  { value: 'AUTO' as const, label: '自动', icon: Route, title: '由系统选择适合的运行方式' },
  { value: 'FAST' as const, label: '快速', icon: Zap, title: '优先响应速度' },
  { value: 'DEEP' as const, label: '深度', icon: Network, title: '进行多轮检索与证据核对' },
]

const modeLabel = computed(() => modeOptions.find((option) => option.value === mode.value)?.label ?? '自动')

const createConversation = useMutation({
  mutationFn: ({ title, settings }: { title: string; settings: ConversationSettings }) =>
    api.post<Conversation>('/api/v1/conversations', { title, settings }),
  onSuccess: async (conversation) => {
    selectedConversationId.value = conversation.id
    await router.replace({ path: '/chat', query: { conversation: conversation.id } })
    await queryClient.invalidateQueries({ queryKey: ['conversations'] })
    queryClient.setQueryData(['conversation', conversation.id], conversation)
  },
})

const updateSettings = useMutation({
  mutationFn: ({ id, settings }: { id: string; settings: ConversationSettings }) =>
    api.patch<Conversation>(`/api/v1/conversations/${id}`, { settings }),
  onSuccess: (conversation) => {
    queryClient.setQueryData(['conversation', conversation.id], conversation)
    void queryClient.invalidateQueries({ queryKey: ['conversations'] })
  },
})

function currentSettings(): ConversationSettings {
  return {
    mode: mode.value,
    scope: { knowledgeBaseIds: [...selectedKnowledgeBaseIds.value], documentIds: [] },
    filters: metadataFilters.value.map((filter) => ({ ...filter })),
  }
}

function resizeComposer() {
  if (!composer.value) return
  composer.value.style.height = 'auto'
  composer.value.style.height = `${Math.min(composer.value.scrollHeight, 220)}px`
}

function suggestionRequest(refresh: boolean): QuestionSuggestionRequest {
  return {
    mode: mode.value,
    scope: { knowledgeBaseIds: [...selectedKnowledgeBaseIds.value], documentIds: [] },
    filters: metadataFilters.value.map((filter) => ({ ...filter })),
    refresh,
    currentBatchId: suggestionResponse.value?.batchId ?? null,
  }
}

async function loadSuggestions(refresh = false) {
  if (!suggestionsVisible.value) return
  suggestionController?.abort()
  const controller = new AbortController()
  const sequence = ++suggestionSequence
  const clientKey = currentSuggestionClientKey.value
  suggestionController = controller
  if (refresh) suggestionRefreshing.value = true
  else suggestionState.value = 'loading'

  try {
    const response = await api.post<QuestionSuggestionResponse>(
      '/api/v1/chat/question-suggestions',
      suggestionRequest(refresh),
      { signal: controller.signal },
    )
    if (controller.signal.aborted || sequence !== suggestionSequence) return
    suggestionResponse.value = response
    suggestionClientKey.value = clientKey
    suggestionState.value = 'ready'
    if (response.emptyReason === 'CATALOG_BUILDING') {
      window.clearTimeout(suggestionTimer)
      suggestionTimer = window.setTimeout(() => {
        if (
          suggestionsVisible.value &&
          currentSuggestionClientKey.value === clientKey &&
          suggestionResponse.value?.emptyReason === 'CATALOG_BUILDING'
        ) {
          void loadSuggestions()
        }
      }, 2500)
    }
  } catch {
    if (controller.signal.aborted || sequence !== suggestionSequence) return
    if (!suggestionResponse.value || suggestionClientKey.value !== clientKey) {
      suggestionResponse.value = null
      suggestionState.value = 'error'
    }
  } finally {
    if (sequence === suggestionSequence) {
      suggestionRefreshing.value = false
      suggestionController = null
    }
  }
}

function scheduleSuggestions() {
  window.clearTimeout(suggestionTimer)
  suggestionController?.abort()
  if (!suggestionsVisible.value) return
  const clientKey = currentSuggestionClientKey.value
  if (suggestionResponse.value && suggestionClientKey.value === clientKey) {
    suggestionState.value = 'ready'
    return
  }
  suggestionResponse.value = null
  suggestionState.value = 'loading'
  suggestionTimer = window.setTimeout(() => void loadSuggestions(), 400)
}

async function selectSuggestion(text: string) {
  queryText.value = text
  scopeOpen.value = false
  await nextTick()
  resizeComposer()
  composer.value?.focus()
  composer.value?.setSelectionRange(text.length, text.length)
}

function distanceFromLatest() {
  const viewport = messagesViewport.value
  if (!viewport) return 0
  return Math.max(0, viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight)
}

function handleMessagesScroll() {
  const nearLatest = distanceFromLatest() <= 120
  userDetachedFromLatest.value = !nearLatest
  showReturnToLatest.value = !nearLatest
}

async function scrollToLatest(behavior: ScrollBehavior = 'smooth', force = false) {
  if (userDetachedFromLatest.value && !force) return
  await nextTick()

  const applyScroll = () => {
    const viewport = messagesViewport.value
    if (!viewport) return
    if (behavior === 'auto') viewport.scrollTop = viewport.scrollHeight
    else viewport.scrollTo({ top: viewport.scrollHeight, behavior })
  }

  applyScroll()
  if (force) {
    userDetachedFromLatest.value = false
    showReturnToLatest.value = false
  }
  if (behavior !== 'auto') return

  for (let frame = 0; frame < 2; frame += 1) {
    await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
    applyScroll()
  }
}

function returnToLatest() {
  userDetachedFromLatest.value = false
  showReturnToLatest.value = false
  void scrollToLatest('smooth', true)
}

function eventStreamFor(run: RunRecord) {
  streamController?.abort()
  const controller = new AbortController()
  streamController = controller
  void streamRunEvents(run.runId, {
    after: run.chatSequence,
    channel: 'chat',
    signal: controller.signal,
    onEvent: (event) => {
      runs.applyChatEvent(event)
      void scrollToLatest()
      if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(event.type)) {
        void queryClient.invalidateQueries({ queryKey: ['conversations'] })
        void queryClient.invalidateQueries({ queryKey: ['conversation-messages', run.conversationId] })
      }
    },
  }).catch((error) => {
    if (!controller.signal.aborted) runs.markDisconnected(run.runId, readableError(error))
  })
}

function optimisticUserMessage(runId: string, text: string, requestedMode: RunMode): ConversationMessage {
  return {
    id: `optimistic-${runId}`,
    role: 'user',
    content: text,
    citations: [],
    restricted: false,
    runId,
    traceAvailable: false,
    reprocessable: false,
    runStatus: 'RUNNING',
    requestedMode,
    selectedMode: null,
    answerMode: null,
    retrievalHealth: null,
    evidenceCount: null,
    latencyMs: null,
    assistantName: null,
    assistantProfileVersion: null,
    createdAt: new Date().toISOString(),
  }
}

async function submit() {
  const text = queryText.value.trim()
  if (!text || isRunning.value) return
  submitError.value = ''
  let conversationId = selectedConversationId.value
  try {
    if (!conversationId) {
      const conversation = await createConversation.mutateAsync({
        title: text.length > 42 ? `${text.slice(0, 42)}…` : text,
        settings: currentSettings(),
      })
      conversationId = conversation.id
    }
    const accepted = await api.post<RunAccepted>(`/api/v1/conversations/${conversationId}/runs`, {
      query: text,
      mode: mode.value,
      scope: { knowledgeBaseIds: selectedKnowledgeBaseIds.value, documentIds: [] },
      filters: metadataFilters.value,
    } satisfies CreateRunRequest)
    runs.register(accepted.runId, { conversationId, query: text, requestedMode: accepted.requestedMode })
    queryClient.setQueryData<ConversationMessage[]>(
      ['conversation-messages', conversationId],
      (messages = []) => [...messages.filter((message) => message.id !== `optimistic-${accepted.runId}`),
        optimisticUserMessage(accepted.runId, text, accepted.requestedMode)],
    )
    void queryClient.invalidateQueries({ queryKey: ['conversation-messages', conversationId] })
    queryText.value = ''
    resizeComposer()
    eventStreamFor(runs.records[accepted.runId])
    void scrollToLatest('smooth', true)
  } catch (error) {
    submitError.value = readableError(error)
  }
}

function sourceQuery(runId: string) {
  return (messagesQuery.data.value ?? []).find(
    (message) => message.role === 'user' && message.runId === runId,
  )?.content ?? ''
}

async function reprocess(runId: string) {
  if (isRunning.value || reprocessingRunId.value) return
  submitError.value = ''
  reprocessingRunId.value = runId
  try {
    const accepted = await api.post<RunAccepted>(`/api/v1/runs/${runId}/reprocess`, {})
    const query = sourceQuery(runId)
    runs.register(accepted.runId, {
      conversationId: selectedConversationId.value,
      query,
      requestedMode: accepted.requestedMode,
    })
    queryClient.setQueryData<ConversationMessage[]>(
      ['conversation-messages', selectedConversationId.value],
      (messages = []) => messages.flatMap((message) => {
        if (message.role === 'assistant' && message.runId === runId) return []
        if (message.role === 'user' && message.runId === runId) {
          return [{
            ...message,
            runId: accepted.runId,
            runStatus: 'RUNNING' as const,
            requestedMode: accepted.requestedMode,
            selectedMode: null,
            answerMode: null,
            retrievalHealth: null,
            evidenceCount: null,
            latencyMs: null,
          }]
        }
        return [message]
      }),
    )
    eventStreamFor(runs.records[accepted.runId])
    void queryClient.invalidateQueries({
      queryKey: ['conversation-messages', selectedConversationId.value],
    })
  } catch (error) {
    submitError.value = readableError(error)
  } finally {
    reprocessingRunId.value = ''
  }
}

async function loadTrace(runId: string) {
  if (historicalTraces.value[runId] || traceLoading.value[runId]) return
  traceLoading.value = { ...traceLoading.value, [runId]: true }
  try {
    const trace = await api.get<RunTrace>(`/api/v1/runs/${runId}/trace`)
    historicalTraces.value = { ...historicalTraces.value, [runId]: trace }
  } catch (error) {
    submitError.value = readableError(error)
  } finally {
    traceLoading.value = { ...traceLoading.value, [runId]: false }
  }
}

function messageRunStatus(message: ConversationMessage): RunStatus | undefined {
  if (message.runStatus === 'COMPLETED') return 'completed'
  if (message.runStatus === 'FAILED') return 'failed'
  if (message.runStatus === 'CANCELLED') return 'cancelled'
  if (message.runStatus === 'QUEUED') return 'accepted'
  if (message.runStatus === 'RUNNING') return 'running'
  return undefined
}

async function cancelRun() {
  const run = latestRun.value
  if (!run) return
  try {
    await api.delete<void>(`/api/v1/runs/${run.runId}`)
    runs.markCancelled(run.runId)
    streamController?.abort()
  } catch (error) {
    runs.markDisconnected(run.runId, readableError(error))
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void submit()
  }
}

function showCitations(
  citations: Citation[],
  evidenceIndex?: number,
  selectedMode?: Exclude<RunMode, 'AUTO'> | null,
) {
  activeCitations.value = citations
  activeEvidenceIndex.value = evidenceIndex ?? citations[0]?.index ?? 1
  activeCitationMode.value = selectedMode ?? null
  citationPanelOpen.value = true
}

async function openCitation(citation: Citation) {
  if (!citation.documentId) return
  try {
    const detail = await api.get<DocumentDetail>(`/api/v1/documents/${citation.documentId}`)
    citationPanelOpen.value = false
    await router.push({
      path: `/knowledge/${detail.knowledgeBaseId}/documents/${citation.documentId}`,
      query: {
        version: citation.documentVersionId,
        chunk: citation.chunkId,
        tab: 'chunks',
        ...(citation.pageNumber != null ? { page: String(citation.pageNumber) } : {}),
        ...(citation.sourceStart != null ? { sourceStart: String(citation.sourceStart) } : {}),
        ...(citation.sourceEnd != null ? { sourceEnd: String(citation.sourceEnd) } : {}),
      },
    })
  } catch (error) {
    submitError.value = readableError(error)
  }
}

function clearScope() {
  selectedKnowledgeBaseIds.value = []
  metadataFilters.value = []
  scopeOpen.value = false
}

watch(
  () => route.query.conversation,
  (value) => {
    const id = typeof value === 'string' ? value : ''
    if (id !== selectedConversationId.value) selectedConversationId.value = id
  },
)

watch(
  () => conversationQuery.data.value,
  async (conversation) => {
    if (!conversation) return
    applyingSettings = true
    mode.value = conversation.settings?.mode ?? 'AUTO'
    selectedKnowledgeBaseIds.value = [...(conversation.settings?.scope?.knowledgeBaseIds ?? [])]
    metadataFilters.value = (conversation.settings?.filters ?? []).map((filter) => ({ ...filter }))
    await nextTick()
    applyingSettings = false
  },
  { immediate: true },
)

watch(
  [mode, selectedKnowledgeBaseIds, metadataFilters],
  () => {
    if (applyingSettings || !selectedConversationId.value) return
    window.clearTimeout(settingsTimer)
    settingsTimer = window.setTimeout(() => {
      updateSettings.mutate({ id: selectedConversationId.value, settings: currentSettings() })
    }, 450)
  },
  { deep: true },
)

watch(
  [suggestionsVisible, currentSuggestionClientKey],
  scheduleSuggestions,
  { immediate: true, deep: true },
)

watch(selectedConversationId, () => {
  scopeOpen.value = false
  citationPanelOpen.value = false
  historicalTraces.value = {}
  userDetachedFromLatest.value = false
  showReturnToLatest.value = false
  const run = latestRun.value
  if (run && ['accepted', 'running', 'disconnected'].includes(run.status)) eventStreamFor(run)
  else streamController?.abort()
  void scrollToLatest('auto', true)
})

watch(
  () => messagesQuery.data.value,
  () => void scrollToLatest('auto'),
  { immediate: true, flush: 'post' },
)

onMounted(() => {
  contentResizeObserver = new ResizeObserver(() => {
    if (!userDetachedFromLatest.value) void scrollToLatest('auto')
  })
  if (messagesContent.value) contentResizeObserver.observe(messagesContent.value)
  void scrollToLatest('auto', true)
})

onBeforeUnmount(() => {
  streamController?.abort()
  suggestionController?.abort()
  contentResizeObserver?.disconnect()
  window.clearTimeout(settingsTimer)
  window.clearTimeout(suggestionTimer)
})
</script>

<template>
  <section class="relative flex h-dvh min-w-0 flex-col overflow-hidden bg-white">
    <header class="flex h-[68px] shrink-0 items-center border-b border-paper-200 px-8">
      <div class="flex min-w-0 flex-1 items-center gap-2.5">
        <h1 class="truncate text-[15px] font-semibold text-ink-900">
          {{ conversationQuery.data.value?.title || '新对话' }}
        </h1>
        <span class="shrink-0 rounded-md bg-paper-100 px-2 py-1 text-[11px] font-semibold text-ink-500">
          {{ modeLabel }}模式
        </span>
      </div>
      <button
        v-if="latestAnswerEvidence.citations.length"
        type="button"
        class="icon-button size-9 rounded-md text-ink-500 hover:bg-brand-50 hover:text-brand-700"
        :title="`查看证据（${latestAnswerEvidence.citations.length} 条）`"
        :aria-label="`查看证据，共 ${latestAnswerEvidence.citations.length} 条`"
        @click="showCitations(latestAnswerEvidence.citations, undefined, latestAnswerEvidence.mode)"
      >
        <BookOpenText :size="18" stroke-width="1.8" aria-hidden="true" />
      </button>
    </header>

    <div ref="messagesViewport" class="scrollbar-chat min-h-0 flex-1 overflow-y-auto scroll-pb-72" @scroll.passive="handleMessagesScroll">
      <div ref="messagesContent" class="mx-auto min-h-full w-full max-w-[860px] px-6 pb-72">
        <ErrorState
          v-if="selectedConversationId && (messagesQuery.isError.value || conversationQuery.isError.value)"
          class="mt-10"
          :message="readableError(messagesQuery.error.value || conversationQuery.error.value)"
          @retry="messagesQuery.refetch(); conversationQuery.refetch()"
        />

        <div v-else-if="selectedConversationId && messagesQuery.isPending.value" class="space-y-8 py-12">
          <div v-for="item in 3" :key="item" class="h-16 animate-pulse rounded-md bg-paper-100" />
        </div>

        <div v-else-if="showWelcome" class="h-full" aria-hidden="true" />

        <div v-else>
          <template v-for="message in messagesQuery.data.value" :key="message.id">
            <ChatMessage
              :role="message.role"
              :content="message.content"
              :run-id="message.runId"
              :citations="message.citations"
              :selected-mode="message.selectedMode"
              :answer-mode="message.answerMode"
              :retrieval-health="message.retrievalHealth"
              :evidence-count="message.evidenceCount"
              :latency-ms="message.latencyMs"
              :trace="message.runId ? historicalTraces[message.runId] : null"
              :trace-available="message.traceAvailable"
              :trace-loading="message.runId ? traceLoading[message.runId] : false"
              :run-status="messageRunStatus(message)"
              :reprocessable="message.reprocessable"
              :reprocess-pending="reprocessingRunId === message.runId"
              :reprocess-disabled="isRunning"
              @open-citations="showCitations"
              @load-trace="loadTrace"
              @reprocess="reprocess"
            />

            <template v-if="message.role === 'user' && showLatestRun && latestRun && message.runId === latestRun.runId">
              <ChatMessage
                role="assistant"
                :run-id="latestRun.runId"
                :content="latestRun.answer"
                :pending="isRunning"
                :citations="latestRun.citations"
                :selected-mode="latestRun.selectedMode === 'FAST' || latestRun.selectedMode === 'DEEP' ? latestRun.selectedMode : null"
                :answer-mode="latestRun.answerMode"
                :retrieval-health="latestRun.retrievalHealth"
                :evidence-count="latestRun.evidenceCount"
                :trace="latestRun.trace"
                :trace-available="latestRun.trace?.traceAvailable"
                :run-status="latestRun.status"
                :started-at="latestRun.startedAt"
                :reprocessable="['completed', 'failed', 'cancelled'].includes(latestRun.status)"
                :reprocess-disabled="isRunning"
                @open-citations="showCitations"
                @reprocess="reprocess"
              />
              <p v-if="latestRun.error && !latestRun.answer" class="my-4 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">
                {{ latestRun.error }}
              </p>
            </template>
          </template>

          <template v-if="showLatestRun && latestRun && !liveUserMessage">
            <ChatMessage role="user" :content="latestRun.query || ''" />
            <ChatMessage
              role="assistant"
              :run-id="latestRun.runId"
              :content="latestRun.answer"
              :pending="isRunning"
              :citations="latestRun.citations"
              :selected-mode="latestRun.selectedMode === 'FAST' || latestRun.selectedMode === 'DEEP' ? latestRun.selectedMode : null"
              :answer-mode="latestRun.answerMode"
              :retrieval-health="latestRun.retrievalHealth"
              :evidence-count="latestRun.evidenceCount"
              :trace="latestRun.trace"
              :trace-available="latestRun.trace?.traceAvailable"
              :run-status="latestRun.status"
              :started-at="latestRun.startedAt"
              :reprocessable="['completed', 'failed', 'cancelled'].includes(latestRun.status)"
              :reprocess-disabled="isRunning"
              @open-citations="showCitations"
              @reprocess="reprocess"
            />
            <p v-if="latestRun.error && !latestRun.answer" class="my-4 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">
              {{ latestRun.error }}
            </p>
          </template>
        </div>
        <div class="h-2" />
      </div>
    </div>

    <button
      v-if="showReturnToLatest && !showWelcome"
      type="button"
      class="absolute bottom-[188px] left-1/2 z-20 inline-flex h-9 -translate-x-1/2 items-center gap-1.5 rounded-full border border-paper-200 bg-white px-3 text-xs font-medium text-ink-700 shadow-panel transition-colors hover:border-brand-200 hover:text-brand-700"
      @click="returnToLatest"
    >
      <ArrowDown :size="15" aria-hidden="true" />
      返回最新
    </button>

    <footer
      class="z-20 px-8"
      :class="showWelcome ? 'chat-welcome-shell absolute left-1/2 w-full max-w-[964px] -translate-x-1/2' : 'absolute inset-x-0 bottom-0 bg-white/96 py-4'"
    >
      <div class="mx-auto w-full" :class="showWelcome ? 'max-w-[900px]' : 'max-w-[860px]'">
        <div v-if="showWelcome" class="chat-welcome-heading">
          <Sparkles :size="22" stroke-width="1.8" aria-hidden="true" />
          <p class="text-[28px] font-semibold leading-tight">今天想了解什么？</p>
        </div>
        <Transition name="welcome-suggestions">
          <QuestionSuggestions
            v-if="suggestionsVisible"
            :items="suggestionResponse?.suggestions ?? []"
            :loading="suggestionState === 'loading'"
            :refreshing="suggestionRefreshing"
            :error="suggestionState === 'error'"
            :empty-reason="suggestionResponse?.emptyReason ?? null"
            @select="selectSuggestion"
            @refresh="loadSuggestions(true)"
            @retry="loadSuggestions()"
          />
        </Transition>
        <p v-if="submitError" class="mb-2 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
          {{ submitError }}
        </p>
        <div class="chat-composer" :class="showWelcome ? 'chat-composer-welcome' : 'chat-composer-conversation'">
          <textarea
            ref="composer"
            v-model="queryText"
            rows="1"
            aria-label="输入问题"
            class="chat-textarea"
            placeholder="输入问题，越具体，回答越准确"
            :disabled="isRunning"
            @input="resizeComposer"
            @keydown="handleComposerKeydown"
          />

          <div class="chat-input-footer">
            <div class="chat-tools" role="group" aria-label="回答设置">
              <button
                v-for="option in modeOptions"
                :key="option.value"
                type="button"
                class="chat-tool-button"
                :class="{ active: mode === option.value }"
                :title="option.title"
                :aria-pressed="mode === option.value"
                @click="mode = option.value"
              >
                <span class="chat-tool-icon" aria-hidden="true">
                  <component :is="option.icon" :size="16" />
                </span>
                <span>{{ option.label }}</span>
              </button>
              <div class="relative">
              <button
                type="button"
                class="chat-tool-button max-w-52"
                :class="{ active: scopeOpen || selectedKnowledgeBaseIds.length > 0 }"
                :aria-expanded="scopeOpen"
                @click="scopeOpen = !scopeOpen"
              >
                <span class="chat-tool-icon" aria-hidden="true">
                  <BookOpenCheck :size="16" />
                </span>
                <span class="truncate">{{ scopeLabel }}</span>
                <ChevronDown :size="13" class="chat-tool-chevron" :class="{ 'rotate-180': scopeOpen }" aria-hidden="true" />
              </button>
              <div v-if="scopeOpen" class="absolute bottom-[calc(100%+10px)] left-0 z-30 w-80 rounded-[14px] border border-paper-200 bg-white p-2 shadow-panel">
                <div class="flex h-9 items-center justify-between px-2">
                  <p class="text-xs font-semibold text-ink-800">限定知识库</p>
                  <button v-if="selectedKnowledgeBaseIds.length" type="button" class="text-xs font-medium text-brand-700" @click="clearScope">清除</button>
                </div>
                <div class="scrollbar-subtle max-h-60 overflow-y-auto">
                  <label
                    v-for="item in knowledgeQuery.data.value"
                    :key="item.id"
                    class="flex h-10 items-center gap-3 rounded-md px-2 text-sm text-ink-700 hover:bg-paper-100"
                  >
                    <span class="relative flex size-4 items-center justify-center rounded border border-paper-300" :class="selectedKnowledgeBaseIds.includes(item.id) ? 'border-brand-600 bg-brand-600 text-white' : 'bg-white'">
                      <Check v-if="selectedKnowledgeBaseIds.includes(item.id)" :size="12" stroke-width="3" aria-hidden="true" />
                      <input v-model="selectedKnowledgeBaseIds" type="checkbox" :value="item.id" class="absolute inset-0 opacity-0" />
                    </span>
                    <span class="min-w-0 flex-1 truncate">{{ item.name }}</span>
                  </label>
                  <p v-if="knowledgeQuery.isPending.value" class="px-2 py-4 text-xs text-ink-500">正在读取知识库…</p>
                  <p v-else-if="knowledgeQuery.isError.value" class="px-2 py-4 text-xs text-coral-700">知识库列表暂时不可用</p>
                  <p v-else-if="!knowledgeQuery.data.value?.length" class="px-2 py-4 text-xs text-ink-500">还没有可用知识库</p>
                </div>
              </div>
              </div>

              <MetadataFilterBuilder v-model="metadataFilters" :knowledge-base-ids="selectedKnowledgeBaseIds" composer />
            </div>

            <button
              v-if="isRunning"
              type="button"
              class="chat-send-button chat-stop-button ml-auto"
              title="停止生成"
              aria-label="停止生成"
              @click="cancelRun"
            >
              <Square :size="14" fill="currentColor" aria-hidden="true" />
            </button>
            <button
              v-else
              type="button"
              class="chat-send-button ml-auto"
              title="发送"
              aria-label="发送消息"
              :disabled="!queryText.trim() || createConversation.isPending.value"
              @click="submit"
            >
              <LoaderCircle v-if="createConversation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />
              <ArrowUp v-else :size="18" stroke-width="2.5" aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>
    </footer>

    <button
      v-if="scopeOpen"
      type="button"
      class="fixed inset-0 z-10 cursor-default"
      aria-label="关闭知识范围选择"
      @click="scopeOpen = false"
    />

    <CitationPanel
      :open="citationPanelOpen"
      :citations="activeCitations"
      :selected-evidence-index="activeEvidenceIndex"
      :mode="activeCitationMode"
      @close="citationPanelOpen = false"
      @open-source="openCitation"
    />
  </section>
</template>
