<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import {
  BookOpenText,
  Check,
  Copy,
  Ellipsis,
  LoaderCircle,
  RefreshCw,
  Route,
} from 'lucide-vue-next'
import ProcessingTrace from '@/components/ProcessingTrace.vue'
import {
  displayIndexMap,
  EVIDENCE_REFERENCE_PATTERN,
  evidenceIndex,
  filterReferencedCitations,
  indexCitations,
  referencedEvidenceIndices,
} from '@/lib/citations'
import type { RunStatus } from '@/stores/runs'
import type { AnswerMode, Citation, RetrievalHealth, RunMode, RunTrace } from '@/types/api'

const props = defineProps<{
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  runId?: string | null
  pending?: boolean
  citations?: Citation[]
  selectedMode?: Exclude<RunMode, 'AUTO'> | null
  answerMode?: AnswerMode | null
  retrievalHealth?: RetrievalHealth | null
  evidenceCount?: number | null
  latencyMs?: number | null
  trace?: RunTrace | null
  traceAvailable?: boolean
  traceLoading?: boolean
  runStatus?: RunStatus
  startedAt?: string | null
  reprocessable?: boolean
  reprocessPending?: boolean
  reprocessDisabled?: boolean
}>()

const emit = defineEmits<{
  openCitations: [citations: Citation[], evidenceIndex?: number, selectedMode?: Exclude<RunMode, 'AUTO'> | null]
  loadTrace: [runId: string]
  reprocess: [runId: string]
}>()

const traceRef = ref<{ open: () => void } | null>(null)
const copied = ref(false)
const moreOpen = ref(false)
let copiedTimer: number | undefined

const referencedCitations = computed(() => filterReferencedCitations(props.content, props.citations ?? []))

const renderedContent = computed(() => {
  if (!props.content) return ''
  const indexedCitations = indexCitations(props.citations ?? [])
  const evidenceIndices = new Set(indexedCitations.map((item) => item.rawIndex))
  const expectedEvidenceCount = Math.max(
    props.evidenceCount ?? 0,
    ...evidenceIndices,
  )
  const referencedIndices = referencedEvidenceIndices(props.content)
    .filter((index) => index > 0 && index <= expectedEvidenceCount)
  const displayIndices = displayIndexMap(referencedIndices)
  const html = marked.parse(props.content, {
    async: false,
    gfm: true,
    breaks: true,
  }) as string
  const sanitized = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
  })

  const document = new DOMParser().parseFromString(`<div id="answer-root">${sanitized}</div>`, 'text/html')
  const root = document.querySelector<HTMLDivElement>('#answer-root')
  if (!root) return sanitized

  for (const table of root.querySelectorAll('table')) {
    const wrapper = document.createElement('div')
    wrapper.className = 'assistant-table-scroll'
    table.before(wrapper)
    wrapper.append(table)
  }

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const textNodes: Text[] = []
  let current = walker.nextNode()
  while (current) {
    const parent = current.parentElement
    if (parent && !parent.closest('code, pre, a, button')) textNodes.push(current as Text)
    current = walker.nextNode()
  }

  for (const textNode of textNodes) {
    const value = textNode.data
    const matches = [...value.matchAll(EVIDENCE_REFERENCE_PATTERN)]
      .filter((match) => {
        const index = evidenceIndex(match)
        return evidenceIndices.has(index) || (index > 0 && index <= expectedEvidenceCount)
      })
    if (!matches.length) continue

    const fragment = document.createDocumentFragment()
    let cursor = 0
    for (const match of matches) {
      const offset = match.index ?? 0
      if (offset > cursor) fragment.append(document.createTextNode(value.slice(cursor, offset)))
      const rawEvidenceIndex = evidenceIndex(match)
      const displayIndex = displayIndices.get(rawEvidenceIndex) ?? rawEvidenceIndex
      if (evidenceIndices.has(rawEvidenceIndex)) {
        const button = document.createElement('button')
        button.type = 'button'
        button.className = 'evidence-reference'
        button.dataset.evidenceIndex = String(rawEvidenceIndex)
        button.title = `查看证据 ${displayIndex}`
        button.setAttribute('aria-label', `查看证据 ${displayIndex}`)
        button.textContent = `[${displayIndex}]`
        fragment.append(button)
      } else {
        const pendingReference = document.createElement('span')
        pendingReference.className = 'evidence-reference evidence-reference-pending'
        pendingReference.title = '证据正在整理'
        pendingReference.textContent = `[${displayIndex}]`
        fragment.append(pendingReference)
      }
      cursor = offset + match[0].length
    }
    if (cursor < value.length) fragment.append(document.createTextNode(value.slice(cursor)))
    textNode.replaceWith(fragment)
  }

  return root.innerHTML
})

const conversational = computed(() => props.answerMode === 'CONVERSATIONAL')

const trustLabel = computed(() => {
  switch (props.answerMode) {
    case 'GROUNDED':
    case 'ANSWER_WITH_EVIDENCE': return '内部资料支撑'
    case 'PARTIAL_GROUNDED': return '部分资料支撑'
    case 'GENERAL_KNOWLEDGE': return '模型通用知识 · 未引用内部资料'
    case 'NO_ENTERPRISE_EVIDENCE':
    case 'NO_EVIDENCE': return '未找到足够的内部依据'
    case 'TEMPORARILY_UNAVAILABLE': return '可重新处理'
    default: return ''
  }
})

const hasMoreMenu = computed(() => Boolean(
  referencedCitations.value.length || props.traceAvailable || props.trace?.traceAvailable,
))

async function copyAnswer() {
  if (!props.content) return
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(props.content)
  } else {
    const textarea = document.createElement('textarea')
    textarea.value = props.content
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  copied.value = true
  window.clearTimeout(copiedTimer)
  copiedTimer = window.setTimeout(() => { copied.value = false }, 1_600)
}

function loadTrace() {
  if (props.runId) emit('loadTrace', props.runId)
}

function openTrace() {
  moreOpen.value = false
  traceRef.value?.open()
}

function openSources() {
  moreOpen.value = false
  if (referencedCitations.value.length) emit('openCitations', referencedCitations.value, undefined, props.selectedMode)
}

function handleAnswerClick(event: MouseEvent) {
  const target = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-evidence-index]')
  if (!target || !referencedCitations.value.length) return
  const rawEvidenceIndex = Number(target.dataset.evidenceIndex)
  if (Number.isFinite(rawEvidenceIndex)) emit('openCitations', referencedCitations.value, rawEvidenceIndex, props.selectedMode)
}

function reprocess() {
  moreOpen.value = false
  if (props.runId) emit('reprocess', props.runId)
}

function closeMenu(event: MouseEvent) {
  if (!(event.target as HTMLElement).closest('[data-answer-menu]')) moreOpen.value = false
}

onMounted(() => document.addEventListener('click', closeMenu))
onBeforeUnmount(() => {
  document.removeEventListener('click', closeMenu)
  window.clearTimeout(copiedTimer)
})
</script>

<template>
  <article
    v-if="role === 'user'"
    class="flex justify-end py-4"
  >
    <p class="max-w-[72%] whitespace-pre-wrap break-words rounded-lg bg-paper-100 px-4 py-3 text-[15px] leading-7 text-ink-900">
      {{ content }}
    </p>
  </article>

  <article v-else class="py-7" :class="{ 'border-t border-paper-200': role === 'assistant' }">
    <ProcessingTrace
      v-if="role === 'assistant' && (runId || pending || latencyMs != null)"
      ref="traceRef"
      :trace="trace"
      :trace-available="traceAvailable"
      :trace-loading="traceLoading"
      :pending="pending"
      :answer-started="Boolean(content)"
      :run-status="runStatus"
      :started-at="startedAt"
      :latency-ms="latencyMs"
      @load-trace="loadTrace"
    />

    <div
      v-if="content"
      class="assistant-prose text-[15px] leading-8 text-ink-800"
      v-html="renderedContent"
      @click="handleAnswerClick"
    />
    <div v-else-if="pending && !trace?.nodes.length" class="flex min-h-8 items-center gap-2 text-sm text-ink-500">
      <LoaderCircle :size="15" class="animate-spin text-evidence-700" aria-hidden="true" />
      正在准备回答
    </div>

    <div v-if="content || referencedCitations.length" class="mt-5 flex flex-wrap items-center gap-x-3 gap-y-2">
      <button
        v-if="referencedCitations.length"
        type="button"
        class="inline-flex h-8 items-center gap-1.5 rounded-md border border-evidence-100 bg-evidence-50 px-2.5 text-xs font-semibold text-evidence-700 transition-colors hover:border-evidence-600 hover:bg-white"
        @click="openSources"
      >
        <BookOpenText :size="14" aria-hidden="true" />
        证据 · {{ referencedCitations.length }}
      </button>
      <template v-if="!conversational">
        <span v-if="selectedMode" class="answer-meta">{{ selectedMode === 'DEEP' ? '深度' : '快速' }}</span>
        <span
          v-if="trustLabel"
          class="answer-meta"
          :class="{
            supported: answerMode === 'GROUNDED'
              || answerMode === 'ANSWER_WITH_EVIDENCE'
              || answerMode === 'PARTIAL_GROUNDED',
          }"
        >
          {{ trustLabel }}
        </span>
        <span v-if="referencedCitations.length" class="answer-meta">{{ referencedCitations.length }} 个证据</span>
      </template>
      <span v-if="pending && content" class="inline-flex items-center gap-1.5 text-xs text-ink-400">
        <span class="size-1.5 animate-pulse rounded-full bg-evidence-600" />
        正在生成
      </span>
    </div>

    <div v-if="content" class="answer-actions" aria-label="回答操作">
      <button type="button" class="answer-action" :title="copied ? '已复制' : '复制回答'" :aria-label="copied ? '已复制' : '复制回答'" @click="copyAnswer">
        <Check v-if="copied" :size="16" aria-hidden="true" />
        <Copy v-else :size="16" aria-hidden="true" />
      </button>
      <button
        v-if="reprocessable"
        type="button"
        class="answer-action"
        title="重新处理"
        aria-label="重新处理本次问题"
        :disabled="reprocessDisabled || reprocessPending"
        @click="reprocess"
      >
        <LoaderCircle v-if="reprocessPending" :size="16" class="animate-spin" aria-hidden="true" />
        <RefreshCw v-else :size="16" aria-hidden="true" />
      </button>
      <div v-if="hasMoreMenu" class="relative" data-answer-menu>
        <button type="button" class="answer-action" title="更多操作" aria-label="更多操作" :aria-expanded="moreOpen" @click.stop="moreOpen = !moreOpen">
          <Ellipsis :size="17" aria-hidden="true" />
        </button>
        <div v-if="moreOpen" class="answer-menu">
          <button v-if="traceAvailable || trace?.traceAvailable" type="button" @click="openTrace">
            <Route :size="15" aria-hidden="true" />
            查看处理过程
          </button>
          <button v-if="referencedCitations.length" type="button" @click="openSources">
            <BookOpenText :size="15" aria-hidden="true" />
            查看证据
          </button>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.answer-actions {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: 2px;
  margin-top: 9px;
}

.answer-action {
  display: inline-grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #7c8ba1;
  transition: color 140ms ease, background-color 140ms ease;
}

.answer-action:hover:not(:disabled) {
  background: #f1f5f9;
  color: #273449;
}

.answer-action:disabled {
  cursor: not-allowed;
  opacity: 0.42;
}

.answer-menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + 5px);
  left: 0;
  width: 164px;
  padding: 5px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.1);
}

.answer-menu button {
  display: flex;
  width: 100%;
  min-height: 34px;
  align-items: center;
  gap: 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  padding: 0 9px;
  color: #41516a;
  font-size: 12px;
  text-align: left;
}

.answer-menu button:hover {
  background: #f1f5f9;
  color: #172033;
}

:deep(.evidence-reference) {
  display: inline-flex;
  min-width: 24px;
  height: 22px;
  align-items: center;
  justify-content: center;
  margin: 0 2px;
  border: 0;
  border-radius: 5px;
  background: #eff6ff;
  padding: 0 4px;
  color: #1d4ed8;
  font-size: 0.78em;
  font-weight: 700;
  line-height: 1;
  vertical-align: 0.08em;
  transition: background-color 140ms ease, color 140ms ease;
}

:deep(button.evidence-reference:hover) {
  background: #dbeafe;
  color: #1e40af;
}

:deep(button.evidence-reference:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 2px;
}

:deep(.evidence-reference-pending) {
  cursor: default;
}
</style>
