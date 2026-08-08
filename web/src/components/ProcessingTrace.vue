<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  Check,
  ChevronDown,
  Circle,
  LoaderCircle,
  OctagonX,
  Square,
} from 'lucide-vue-next'
import type { RunTrace, RunTraceNode } from '@/types/api'
import type { RunStatus } from '@/stores/runs'

const props = defineProps<{
  trace?: RunTrace | null
  traceAvailable?: boolean
  traceLoading?: boolean
  pending?: boolean
  answerStarted?: boolean
  runStatus?: RunStatus
  startedAt?: string | null
  latencyMs?: number | null
}>()

const emit = defineEmits<{
  loadTrace: []
}>()

const now = ref(Date.now())
const expanded = ref(Boolean(props.pending && !props.answerStarted))
const manuallyToggled = ref(false)
const openNodeKey = ref<string | null>(null)
const answerStartedAt = ref<number | null>(props.pending && props.answerStarted ? Date.now() : null)
let timer: number | undefined

function timestamp(value?: string | null) {
  if (!value) return null
  const parsed = new Date(value).getTime()
  return Number.isFinite(parsed) ? parsed : null
}

const traceNodes = computed(() => props.trace?.nodes ?? [])

const visibleNodes = computed(() => {
  const nodes = traceNodes.value
  const hasStageTimestamps = nodes.some((node) => timestamp(node.startedAt) != null)

  if (!hasStageTimestamps) return nodes.filter((node) => node.status !== 'WAITING')
  return nodes.filter((node) => timestamp(node.startedAt) != null)
})

const generationNode = computed(() => traceNodes.value.find((node) => node.key === 'generate'))

const effectiveState = computed(() => {
  if (['FAILED', 'CANCELLED', 'COMPLETED'].includes(props.trace?.state ?? '')) {
    return props.trace?.state
  }
  if (props.runStatus === 'failed' || props.runStatus === 'disconnected') return 'FAILED'
  if (props.runStatus === 'cancelled') return 'CANCELLED'
  if (props.runStatus === 'completed') return 'COMPLETED'
  if (generationNode.value?.startedAt || props.answerStarted || props.trace?.state === 'GENERATING') {
    return 'GENERATING'
  }
  if (props.trace?.state) return props.trace.state
  return 'PROCESSING'
})

const canExpand = computed(() => Boolean(
  props.trace?.nodes.length || props.trace?.traceAvailable || props.traceAvailable,
))

const durationMs = computed(() => {
  const started = timestamp(props.trace?.startedAt || props.startedAt)
  const generationStarted = timestamp(generationNode.value?.startedAt)
    ?? timestamp(props.trace?.firstAnswerAt)
    ?? answerStartedAt.value

  if (started != null && generationStarted != null) {
    return Math.max(0, generationStarted - started)
  }
  if (props.trace?.durationMs != null) {
    const generationDuration = generationNode.value?.durationMs
    return generationDuration == null
      ? props.trace.durationMs
      : Math.max(0, props.trace.durationMs - generationDuration)
  }
  if (!props.pending && props.latencyMs != null) return props.latencyMs
  if (started == null) return props.latencyMs ?? 0
  return Math.max(0, now.value - started)
})

const statusLabel = computed(() => {
  if (effectiveState.value === 'FAILED') return '处理未完成'
  if (effectiveState.value === 'CANCELLED') return '已停止'
  if (effectiveState.value === 'COMPLETED') return '已处理'
  if (effectiveState.value === 'GENERATING') return '已处理'
  return '处理中'
})

const durationLabel = computed(() => `${(durationMs.value / 1000).toFixed(1)} s`)

function nodeDuration(node: RunTraceNode) {
  if (node.key === 'generate') return ''
  if (node.durationMs == null) return ''
  if (node.durationMs < 1000) return `${node.durationMs} ms`
  return `${(node.durationMs / 1000).toFixed(1)} s`
}

function hasDetails(node: RunTraceNode) {
  return Boolean(node.details.length || node.goals.length)
}

function toggle() {
  if (!canExpand.value) return
  expanded.value = !expanded.value
  manuallyToggled.value = true
  if (expanded.value && !props.trace && props.traceAvailable) emit('loadTrace')
}

function open() {
  if (!canExpand.value) return
  expanded.value = true
  manuallyToggled.value = true
  if (!props.trace && props.traceAvailable) emit('loadTrace')
}

function toggleNode(node: RunTraceNode) {
  if (!hasDetails(node)) return
  openNodeKey.value = openNodeKey.value === node.key ? null : node.key
}

watch(
  () => props.answerStarted,
  (started, previous) => {
    if (started && !previous) {
      answerStartedAt.value = Date.now()
      if (!manuallyToggled.value) expanded.value = false
    }
  },
)

watch(
  () => props.trace?.firstAnswerAt,
  (started, previous) => {
    if (started && started !== previous && !manuallyToggled.value) expanded.value = false
  },
  { immediate: true },
)

watch(
  () => props.pending,
  (pending, previous) => {
    if (pending && !previous && !props.answerStarted) {
      expanded.value = true
      manuallyToggled.value = false
      answerStartedAt.value = null
    }
  },
)

onMounted(() => {
  timer = window.setInterval(() => {
    if (props.pending && !generationNode.value?.startedAt && !props.answerStarted) {
      now.value = Date.now()
    }
  }, 100)
})

onBeforeUnmount(() => window.clearInterval(timer))

defineExpose({ open })
</script>

<template>
  <section class="processing-trace" aria-live="polite">
    <button
      v-if="canExpand"
      type="button"
      class="trace-summary"
      :aria-expanded="expanded"
      @click="toggle"
    >
      <span class="tabular-nums">{{ statusLabel }} {{ durationLabel }}</span>
      <ChevronDown :size="14" :class="['trace-summary-chevron', { open: expanded }]" aria-hidden="true" />
    </button>
    <div v-else class="trace-summary trace-summary-static">
      <span class="tabular-nums">{{ statusLabel }} {{ durationLabel }}</span>
    </div>

    <Transition name="trace-reveal">
      <div v-if="expanded" class="trace-body">
        <div v-if="traceLoading && !trace" class="trace-loading">
          <LoaderCircle :size="14" class="animate-spin" aria-hidden="true" />
          正在读取处理过程
        </div>
        <TransitionGroup
          v-else-if="visibleNodes.length"
          name="trace-node-reveal"
          tag="ol"
          class="trace-list"
        >
          <li v-for="node in visibleNodes" :key="node.key" class="trace-node" :class="`is-${node.status.toLowerCase()}`">
            <span class="trace-rail" aria-hidden="true" />
            <button
              type="button"
              class="trace-node-row"
              :class="{ expandable: hasDetails(node) }"
              :disabled="!hasDetails(node)"
              :aria-expanded="hasDetails(node) ? openNodeKey === node.key : undefined"
              @click="toggleNode(node)"
            >
              <span class="trace-node-mark" aria-hidden="true">
                <Check v-if="node.status === 'COMPLETED'" :size="12" stroke-width="3" />
                <LoaderCircle v-else-if="node.status === 'RUNNING'" :size="13" class="animate-spin" />
                <AlertTriangle v-else-if="node.status === 'DEGRADED'" :size="13" />
                <OctagonX v-else-if="node.status === 'FAILED'" :size="13" />
                <Square v-else-if="node.status === 'CANCELLED'" :size="10" fill="currentColor" />
                <Circle v-else :size="8" fill="currentColor" stroke-width="0" />
              </span>
              <span class="trace-node-label">{{ node.label }}</span>
              <span class="trace-node-summary">{{ node.summary }}</span>
              <span v-if="nodeDuration(node)" class="trace-node-time tabular-nums">{{ nodeDuration(node) }}</span>
              <ChevronDown
                v-if="hasDetails(node)"
                :size="13"
                :class="['trace-node-chevron', { open: openNodeKey === node.key }]"
                aria-hidden="true"
              />
            </button>

            <Transition name="trace-detail">
              <div v-if="openNodeKey === node.key" class="trace-node-detail">
                <ol v-if="node.goals.length" class="trace-goals">
                  <li v-for="goal in node.goals" :key="goal.index" class="trace-goal">
                    <span class="trace-goal-index">{{ goal.index }}</span>
                    <span class="min-w-0 flex-1">
                      <span class="block truncate text-ink-700">{{ goal.label }}</span>
                      <span class="mt-0.5 block text-xs text-ink-400">{{ goal.summary }}</span>
                    </span>
                  </li>
                </ol>
                <dl v-if="node.details.length" class="trace-details">
                  <template v-for="detail in node.details" :key="`${node.key}-${detail.label}`">
                    <dt>{{ detail.label }}</dt>
                    <dd>{{ detail.value }}</dd>
                  </template>
                </dl>
              </div>
            </Transition>
          </li>
        </TransitionGroup>
        <p v-else class="trace-loading">处理轨迹正在建立</p>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.processing-trace {
  width: min(100%, 760px);
  margin-bottom: 20px;
}

.trace-summary {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  padding: 0;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

button.trace-summary:hover {
  color: #273449;
}

.trace-summary-static {
  cursor: default;
}

.trace-summary-chevron,
.trace-node-chevron {
  flex: 0 0 auto;
  transition: transform 160ms ease;
}

.trace-summary-chevron.open,
.trace-node-chevron.open {
  transform: rotate(180deg);
}

.trace-body {
  padding: 5px 0 8px;
}

.trace-loading {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: 8px;
  color: #7c8ba1;
  font-size: 13px;
}

.trace-list {
  width: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-node {
  position: relative;
  min-height: 38px;
  padding-left: 25px;
}

.trace-rail {
  position: absolute;
  top: 24px;
  bottom: -8px;
  left: 7px;
  width: 1px;
  background: #e2e8f0;
}

.trace-node:last-child .trace-rail {
  display: none;
}

.trace-node-row {
  display: grid;
  width: 100%;
  min-height: 38px;
  grid-template-columns: minmax(92px, auto) minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  padding: 3px 0;
  color: inherit;
  text-align: left;
}

.trace-node-row.expandable:hover .trace-node-label,
.trace-node-row.expandable:hover .trace-node-summary {
  color: #1d4ed8;
}

.trace-node-row:disabled {
  cursor: default;
}

.trace-node-mark {
  position: absolute;
  left: 0;
  display: grid;
  width: 15px;
  height: 15px;
  place-items: center;
  border-radius: 50%;
  background: #f1f5f9;
  color: #94a3b8;
}

.is-completed .trace-node-mark {
  background: #e8f0ff;
  color: #2563eb;
}

.is-running .trace-node-mark {
  background: #eff6ff;
  color: #2563eb;
}

.is-degraded .trace-node-mark {
  background: #fff8e9;
  color: #9a5b12;
}

.is-failed .trace-node-mark,
.is-cancelled .trace-node-mark {
  background: #fff2f4;
  color: #b64252;
}

.trace-node-label {
  color: #41516a;
  font-size: 13px;
  font-weight: 600;
  transition: color 140ms ease;
}

.trace-node-summary {
  min-width: 0;
  overflow: hidden;
  color: #7c8ba1;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 140ms ease;
}

.trace-node-time {
  color: #94a3b8;
  font-size: 12px;
}

.trace-node-chevron {
  color: #94a3b8;
}

.trace-node-detail {
  margin: 1px 0 10px;
  padding: 8px 0 7px 14px;
  border-left: 1px solid #dbe6f5;
}

.trace-details {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 7px 14px;
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
}

.trace-details dt {
  color: #94a3b8;
}

.trace-details dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: #41516a;
}

.trace-goals {
  display: grid;
  gap: 8px;
  margin: 0 0 10px;
  padding: 0;
  list-style: none;
}

.trace-goal {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 9px;
  font-size: 12px;
}

.trace-goal-index {
  display: grid;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  place-items: center;
  border-radius: 50%;
  background: #f1f5f9;
  color: #64748b;
  font-size: 10px;
  font-weight: 600;
}

.trace-reveal-enter-active,
.trace-reveal-leave-active,
.trace-node-reveal-enter-active,
.trace-detail-enter-active,
.trace-detail-leave-active {
  transition: opacity 150ms ease, transform 150ms ease;
}

.trace-reveal-enter-from,
.trace-reveal-leave-to,
.trace-node-reveal-enter-from,
.trace-detail-enter-from,
.trace-detail-leave-to {
  opacity: 0;
  transform: translateY(-3px);
}

@media (prefers-reduced-motion: reduce) {
  .trace-summary-chevron,
  .trace-node-chevron,
  .trace-reveal-enter-active,
  .trace-reveal-leave-active,
  .trace-node-reveal-enter-active,
  .trace-detail-enter-active,
  .trace-detail-leave-active {
    transition: none;
  }
}
</style>
