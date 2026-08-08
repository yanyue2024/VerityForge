<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { ArrowUpRight, BookOpenText, CalendarClock, ChevronDown, FileText, Layers3, LoaderCircle, Target, X } from 'lucide-vue-next'
import DocumentMarkdown from '@/components/DocumentMarkdown.vue'
import { api } from '@/lib/api'
import { indexCitations } from '@/lib/citations'
import { formatDate } from '@/lib/format'
import type { ChunkRow, Citation, RunMode } from '@/types/api'

const props = defineProps<{
  open: boolean
  citations: Citation[]
  selectedEvidenceIndex?: number | null
  mode?: Exclude<RunMode, 'AUTO'> | null
}>()

const emit = defineEmits<{
  close: []
  openSource: [citation: Citation]
}>()

const selectedIndex = ref(0)
const indexedCitations = computed(() => indexCitations(props.citations))
const selectedRecord = computed(() => indexedCitations.value[selectedIndex.value])
const selected = computed(() => selectedRecord.value?.citation)
const parentExpanded = ref(false)
const isFast = computed(() => props.mode === 'FAST')
const goalAssociations = computed(() => selected.value?.goalAssociations ?? [])

const chunksQuery = useQuery(computed(() => ({
  queryKey: ['citation-parent-context', selected.value?.documentVersionId],
  queryFn: () => api.get<ChunkRow[]>(`/api/v1/document-versions/${selected.value?.documentVersionId}/chunks`),
  enabled: props.open && Boolean(selected.value?.documentVersionId && selected.value?.chunkId),
  staleTime: 8 * 60 * 1_000,
})))

const selectedChunk = computed(() => (chunksQuery.data.value ?? []).find(
  (chunk) => chunk.id === selected.value?.chunkId,
))
const citedChild = computed(() => (chunksQuery.data.value ?? []).find(
  (chunk) => chunk.type === 'CHILD' && chunk.id === selected.value?.chunkId,
))
const parentChunk = computed(() => {
  if (!isFast.value && selectedChunk.value?.type === 'PARENT') return selectedChunk.value
  const parentId = (isFast.value ? citedChild.value : selectedChunk.value)?.parentChunkId
  if (!parentId) return undefined
  return (chunksQuery.data.value ?? []).find((chunk) => chunk.type === 'PARENT' && chunk.id === parentId)
})

interface RecalledChild {
  chunk: ChunkRow
  goalIndexes: number[]
}

const recalledChildren = computed<RecalledChild[]>(() => {
  if (isFast.value) {
    return citedChild.value ? [{ chunk: citedChild.value, goalIndexes: [] }] : []
  }

  const chunksById = new Map((chunksQuery.data.value ?? []).map((chunk) => [chunk.id, chunk]))
  const recalledById = new Map<string, RecalledChild>()
  goalAssociations.value.forEach((goal, goalIndex) => {
    goal.recalledChildChunkIds.forEach((chunkId) => {
      const chunk = chunksById.get(chunkId)
      if (!chunk || chunk.type !== 'CHILD') return
      const existing = recalledById.get(chunkId)
      if (existing) {
        if (!existing.goalIndexes.includes(goalIndex)) existing.goalIndexes.push(goalIndex)
      } else {
        recalledById.set(chunkId, { chunk, goalIndexes: [goalIndex] })
      }
    })
  })
  return [...recalledById.values()]
})

const parentMarkdown = computed(() => (
  parentChunk.value?.renderedMarkdown
  || parentChunk.value?.text
  || selected.value?.quote
  || ''
))
const showParentAction = computed(() => Boolean(
  selected.value?.documentVersionId
  && selected.value?.chunkId
  && (chunksQuery.isPending.value || parentMarkdown.value),
))
const parentContextParts = computed(() => {
  const parentText = parentChunk.value?.text
  if (!parentText) return parentMarkdown.value ? [{ markdown: parentMarkdown.value, highlighted: false }] : []

  const matches = recalledChildren.value
    .map(({ chunk }) => {
      const text = chunk.text.trim()
      return { text, start: text ? parentText.indexOf(text) : -1 }
    })
    .filter((match) => match.start >= 0)
    .sort((left, right) => left.start - right.start)

  if (!matches.length) return [{ markdown: parentMarkdown.value, highlighted: false }]

  const parts: Array<{ markdown: string; highlighted: boolean }> = []
  let cursor = 0
  matches.forEach((match) => {
    if (match.start < cursor) return
    if (match.start > cursor) {
      parts.push({ markdown: parentText.slice(cursor, match.start), highlighted: false })
    }
    parts.push({ markdown: parentText.slice(match.start, match.start + match.text.length), highlighted: true })
    cursor = match.start + match.text.length
  })
  if (cursor < parentText.length) parts.push({ markdown: parentText.slice(cursor), highlighted: false })
  return parts
})
const highlightedChildCount = computed(() => parentContextParts.value.filter((part) => part.highlighted).length)

watch(
  [() => props.citations, () => props.selectedEvidenceIndex, () => props.open],
  () => {
    const requested = props.selectedEvidenceIndex
    const matchingIndex = requested == null
      ? -1
      : indexedCitations.value.findIndex((item) => item.rawIndex === requested)
    selectedIndex.value = matchingIndex >= 0 ? matchingIndex : 0
    parentExpanded.value = false
  },
  { immediate: true },
)
watch(selectedIndex, () => { parentExpanded.value = false })
</script>

<template>
  <Transition name="source-panel">
    <div v-if="open" class="fixed inset-0 z-[80]" data-testid="citation-panel">
      <button
        type="button"
        class="absolute inset-y-0 right-[460px] bg-ink-950/10"
        style="left: var(--chat-sidebar-width, 318px)"
        aria-label="关闭证据"
        @click="emit('close')"
      />
      <aside class="absolute inset-y-0 right-0 flex w-[460px] flex-col border-l border-paper-200 bg-white shadow-panel">
        <header class="flex h-16 shrink-0 items-center gap-3 border-b border-paper-200 px-5">
          <span class="flex size-8 items-center justify-center rounded-md bg-evidence-50 text-evidence-700">
            <BookOpenText :size="16" aria-hidden="true" />
          </span>
          <div class="min-w-0 flex-1">
            <h2 class="text-sm font-semibold text-ink-950">回答证据</h2>
            <p class="text-xs text-ink-500">{{ citations.length }} 条可追溯证据</p>
          </div>
          <button class="icon-button" type="button" title="关闭" @click="emit('close')">
            <X :size="18" aria-hidden="true" />
          </button>
        </header>

        <div class="grid min-h-0 flex-1 grid-rows-[auto_minmax(0,1fr)]">
          <div class="scrollbar-subtle flex gap-1 overflow-x-auto border-b border-paper-200 px-4 py-3">
            <button
              v-for="(item, index) in indexedCitations"
              :key="`${item.rawIndex}-${item.citation.chunkId}`"
              type="button"
              class="h-8 shrink-0 rounded-md px-3 text-xs font-semibold transition-colors"
              :class="selectedIndex === index ? 'bg-ink-950 text-white' : 'bg-paper-100 text-ink-600 hover:text-ink-950'"
              @click="selectedIndex = index"
            >
              证据 {{ item.displayIndex }}
            </button>
          </div>

          <div v-if="selected" class="scrollbar-subtle min-h-0 overflow-y-auto overscroll-contain">
            <section class="border-b border-paper-200 px-6 py-6" aria-labelledby="source-document-heading">
              <p id="source-document-heading" class="text-[11px] font-semibold text-ink-500">来源文档</p>
              <div class="mt-3 flex items-start gap-3">
                <span class="flex size-9 shrink-0 items-center justify-center rounded-md bg-paper-100 text-ink-500">
                  <FileText :size="17" aria-hidden="true" />
                </span>
                <div class="min-w-0 flex-1">
                  <h3 class="break-words text-base font-semibold leading-6 text-ink-950">
                    {{ selected.documentTitle || '未命名文档' }}
                  </h3>
                  <p v-if="selected.knowledgeBaseName" class="mt-1 text-xs text-ink-500">
                    {{ selected.knowledgeBaseName }}
                  </p>
                </div>
              </div>

              <div class="mt-4 flex flex-wrap items-center justify-between gap-3">
                <p v-if="selected.documentUpdatedAt" class="flex items-center gap-1.5 text-xs text-ink-500">
                  <CalendarClock :size="13" aria-hidden="true" />
                  文档更新于 {{ formatDate(selected.documentUpdatedAt) }}
                </p>
                <button
                  v-if="selected.documentId"
                  type="button"
                  class="inline-flex h-8 items-center gap-1.5 text-xs font-semibold text-brand-700 hover:text-brand-800"
                  @click="emit('openSource', selected)"
                >
                  在文档中查看
                  <ArrowUpRight :size="14" aria-hidden="true" />
                </button>
              </div>
            </section>

            <section
              v-if="!isFast && goalAssociations.length"
              class="evidence-goals"
              aria-labelledby="evidence-goals-heading"
            >
              <div class="evidence-goals-title">
                <Target :size="15" aria-hidden="true" />
                <h3 id="evidence-goals-heading">关联目标</h3>
                <span>{{ goalAssociations.length }}</span>
              </div>
              <ol class="evidence-goal-list">
                <li v-for="(goal, goalIndex) in goalAssociations" :key="goal.goalId" class="evidence-goal-row">
                  <span class="evidence-goal-index">Goal {{ goalIndex + 1 }}</span>
                  <p>{{ goal.goalQuestion }}</p>
                </li>
              </ol>
            </section>

            <section class="px-6 py-6" aria-labelledby="evidence-detail-heading">
              <div class="flex items-center justify-between gap-3">
                <h3 id="evidence-detail-heading" class="text-sm font-semibold text-ink-950">证据详情</h3>
                <span class="inline-flex h-7 items-center rounded-md bg-evidence-50 px-2.5 text-xs font-semibold text-evidence-700">
                  证据 {{ selectedRecord?.displayIndex ?? selectedIndex + 1 }}
                </span>
              </div>

              <dl v-if="selected.pageNumber != null || selected.sourceStart != null" class="mt-4 flex flex-wrap gap-x-5 gap-y-2 border-y border-paper-200 py-3 text-xs">
                <div v-if="selected.pageNumber != null" class="flex items-center gap-1.5">
                  <dt class="flex items-center gap-1 text-ink-500"><Layers3 :size="13" aria-hidden="true" />页码</dt>
                  <dd class="font-semibold text-ink-800">第 {{ selected.pageNumber }} 页</dd>
                </div>
                <div v-if="selected.sourceStart != null" class="flex items-center gap-1.5">
                  <dt class="text-ink-500">原文位置</dt>
                  <dd class="font-semibold text-ink-800">{{ selected.sourceStart }}–{{ selected.sourceEnd ?? '—' }}</dd>
                </div>
              </dl>

              <div v-if="recalledChildren.length" class="mt-5 space-y-3">
                <article v-for="(item, childIndex) in recalledChildren" :key="item.chunk.id" class="evidence-chunk">
                  <header class="evidence-chunk-header">
                    <div class="min-w-0">
                      <div class="flex flex-wrap items-center gap-1.5">
                        <p class="evidence-kicker">召回子块{{ recalledChildren.length > 1 ? ` ${childIndex + 1}` : '' }}</p>
                        <span
                          v-for="goalIndex in item.goalIndexes"
                          :key="goalIndex"
                          class="evidence-goal-chip"
                        >
                          Goal {{ goalIndex + 1 }}
                        </span>
                      </div>
                      <h4 v-if="item.chunk.contextHeader" class="evidence-path">{{ item.chunk.contextHeader }}</h4>
                    </div>
                    <span class="evidence-token">{{ item.chunk.estimatedTokens }} tokens</span>
                  </header>
                  <DocumentMarkdown
                    class="evidence-chunk-content"
                    :markdown="item.chunk.renderedMarkdown || item.chunk.text"
                  />
                </article>
              </div>
              <p v-else-if="chunksQuery.isPending.value" class="mt-5 flex items-center gap-2 text-sm text-ink-500">
                <LoaderCircle :size="15" class="animate-spin" aria-hidden="true" />
                正在读取召回子块
              </p>
              <p v-else class="mt-5 text-sm leading-7 text-ink-500">
                {{ isFast ? '该子块没有可显示的原文内容。' : '该条历史证据未记录召回子块，仍可查看完整父块。' }}
              </p>

              <div v-if="showParentAction" class="evidence-parent-control">
                <button
                  type="button"
                  class="evidence-parent-toggle"
                  :disabled="chunksQuery.isPending.value"
                  :aria-expanded="parentExpanded"
                  aria-controls="citation-parent-context"
                  @click="parentExpanded = !parentExpanded"
                >
                  <span class="inline-flex items-center gap-2">
                    <LoaderCircle v-if="chunksQuery.isPending.value" :size="15" class="animate-spin" aria-hidden="true" />
                    <Layers3 v-else :size="15" aria-hidden="true" />
                    {{ chunksQuery.isPending.value ? '正在读取父块上下文' : parentExpanded ? '收起完整父块' : '查看完整父块' }}
                  </span>
                  <ChevronDown v-if="!chunksQuery.isPending.value" :size="15" :class="{ 'rotate-180': parentExpanded }" aria-hidden="true" />
                </button>

                <div v-if="parentExpanded && parentMarkdown" id="citation-parent-context" class="evidence-parent-context">
                  <div class="evidence-parent-heading">
                    <div>
                      <p class="text-xs font-semibold text-evidence-800">{{ isFast ? '父块上下文' : 'Deep Read 证据父块' }}</p>
                      <p class="mt-1 text-[11px] text-evidence-700">
                        {{ highlightedChildCount ? `已高亮 ${highlightedChildCount} 个召回子块` : '完整父块上下文' }}
                      </p>
                    </div>
                    <span v-if="parentChunk" class="shrink-0 text-[11px] tabular-nums text-evidence-700/70">{{ parentChunk.estimatedTokens }} tokens</span>
                  </div>
                  <div class="evidence-parent-flow">
                    <template v-for="(part, partIndex) in parentContextParts" :key="partIndex">
                      <div v-if="part.highlighted" class="evidence-current-chunk">
                        <DocumentMarkdown :markdown="part.markdown" />
                      </div>
                      <DocumentMarkdown v-else-if="part.markdown" :markdown="part.markdown" />
                    </template>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>
      </aside>
    </div>
  </Transition>
</template>

<style scoped>
.evidence-chunk { min-width: 0; overflow: hidden; border: 1px solid #dce4ed; border-radius: 7px; background: #fff; }
.evidence-chunk-header { display: flex; min-height: 55px; align-items: flex-start; justify-content: space-between; gap: 12px; border-bottom: 1px solid #e6ebf1; background: #f8fafc; padding: 11px 13px; }
.evidence-kicker { color: #64748b; font-size: 10.5px; font-weight: 680; }
.evidence-path { margin-top: 4px; color: #18263c; font-size: 12px; font-weight: 680; line-height: 1.5; }
.evidence-token { flex-shrink: 0; border-radius: 4px; background: #eaf8f1; padding: 3px 6px; color: #177b55; font-size: 10px; font-weight: 650; }
.evidence-chunk-content { padding: 14px 14px 15px; color: #334155; }
.evidence-goals { border-bottom: 1px solid #e6ebf1; background: #f8fafc; padding: 18px 24px 20px; }
.evidence-goals-title { display: flex; align-items: center; gap: 7px; color: #1d4ed8; }
.evidence-goals-title h3 { color: #172033; font-size: 12px; font-weight: 700; }
.evidence-goals-title > span { display: inline-flex; min-width: 20px; height: 20px; align-items: center; justify-content: center; border-radius: 5px; background: #e8f0ff; padding: 0 6px; color: #255fd7; font-size: 10px; font-weight: 700; }
.evidence-goal-list { margin-top: 12px; }
.evidence-goal-row { display: grid; grid-template-columns: 54px minmax(0, 1fr); align-items: start; gap: 10px; border-left: 2px solid #8ab2ff; padding: 8px 0 8px 10px; }
.evidence-goal-row + .evidence-goal-row { margin-top: 5px; }
.evidence-goal-row p { color: #334155; font-size: 12px; line-height: 1.65; }
.evidence-goal-index, .evidence-goal-chip { display: inline-flex; height: 20px; align-items: center; justify-content: center; border-radius: 4px; background: #eaf1ff; color: #255fd7; font-size: 10px; font-weight: 700; white-space: nowrap; }
.evidence-goal-chip { height: 18px; padding: 0 5px; font-size: 9.5px; }
.evidence-parent-control { margin-top: 18px; border-top: 1px dashed #dce4ed; padding-top: 12px; }
.evidence-parent-toggle { display: flex; width: 100%; min-height: 38px; align-items: center; justify-content: space-between; gap: 12px; border-radius: 6px; padding: 7px 9px; color: #225bd2; font-size: 12.5px; font-weight: 680; text-align: left; transition: background-color 150ms ease, color 150ms ease; }
.evidence-parent-toggle:hover { background: #f1f6ff; color: #1948ad; }
.evidence-parent-toggle:focus-visible { outline: 2px solid #3974e8; outline-offset: 2px; }
.evidence-parent-toggle:disabled { cursor: wait; color: #94a3b8; }
.evidence-parent-toggle > svg { flex-shrink: 0; transition: transform 150ms ease; }
.evidence-parent-context { margin-top: 10px; overflow: hidden; border: 1px solid #cde9dc; border-left: 2px solid #49a97c; border-radius: 0 7px 7px 0; background: #f1fbf6; }
.evidence-parent-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; border-bottom: 1px solid #cde9dc; padding: 12px 13px; }
.evidence-parent-flow, .evidence-parent-body { padding: 14px 13px 16px; color: #334155; }
.evidence-parent-flow > * + * { margin-top: 12px; }
.evidence-current-chunk { border-left: 3px solid #49a97c; border-radius: 0 6px 6px 0; background: #dff4e9; padding: 11px 12px; color: #21483b; box-shadow: inset 0 0 0 1px rgba(73, 169, 124, .12); }
.evidence-current-chunk :deep(.document-table-scroll) { border-color: #a9d8c2; background: rgba(255, 255, 255, .58); }
.evidence-current-chunk :deep(th) { background: #d4ebdf; color: #173f32; }
.evidence-current-chunk :deep(td) { background: rgba(250, 255, 252, .76); }
.evidence-current-chunk :deep(tbody tr:nth-child(even) td) { background: rgba(238, 249, 243, .82); }
</style>
